package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionBehaviorTest {
    @Test
    fun permissionIsRequiredBeforeScanningOrConnecting() = runTest {
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = error("Must not scan without permission")
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = error("Must not connect without permission")
        }
        val session = ControlSession(
            PlatformState(PermissionState.DENIED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )

        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, assertIs<OperationResult.Failure>(session.startScan()).error.code)
        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, assertIs<OperationResult.Failure>(session.connect(DeviceId("device"))).error.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun scanningUsesManufacturerEvidenceAndLeavesSameNameVariantsUnsupported() = runTest {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = advertisements
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = error("Unknown variants must never receive a connection")
        }
        val session = ControlSession(PlatformState(PermissionState.GRANTED, TransportAvailability.Available), transport = transport, scope = backgroundScope)
        assertEquals(OperationResult.Success, session.startScan())
        runCurrent()
        advertisements.emit(Advertisement(DeviceId("known"), "renamed", -40, mapOf(0x5352 to byteArrayOf(0, 1, 0, 1))))
        advertisements.emit(Advertisement(DeviceId("unknown"), "EarFeel i5", -50, mapOf(0x5352 to byteArrayOf(0, 2))))
        runCurrent()

        assertEquals(ScanState.Scanning, session.state.value.scan)
        assertEquals(ModelProfile.I5_ZT, session.state.value.candidates.first().profile)
        assertEquals(null, session.state.value.candidates.last().profile)
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.connect(DeviceId("unknown"))).error.code)
        session.stopScan()
        assertEquals(ScanState.Idle, session.state.value.scan)
    }

    @Test
    fun i5ConnectsAfterSubscriptionAndExposesConfirmedValuesWithoutInventingCaseBattery() = runTest {
        val connection = RecordedConnection(i5Replies, now = { testScheduler.currentTime })
        val transport = RecordedTransport(connection)
        val session = ControlSession(PlatformState(PermissionState.GRANTED, TransportAvailability.Available), transport = transport, scope = backgroundScope)
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(true))).error.code)

        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))

        assertIs<ConnectionState.Connected>(session.state.value.connection)
        val device = requireNotNull(session.state.value.device)
        assertEquals(FieldReading.Known(FieldValue.Number(100), "64"), device.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldReading.NotProvided("ff"), device.fields[DeviceField.CASE_BATTERY])
        assertEquals("125", assertIs<FieldValue.Firmware>(assertIs<FieldReading.Known>(device.fields[DeviceField.FIRMWARE]).value).displayText)
        assertEquals(listOf("08ee00000001010a0002", "08ee00000006020a0008", "08ee00000002010a0003", "08ee00000006030a0009", "08ee00000008010a0009", "08ee0000000d010a000e", "08ee00000004010a0005", "08ee0000000a010a000b", "08ee00000006050a000b", "08ee0000000c010a000d", "08ee00000006070a000d", "08ee00000006040a000a", "08ee0000000e030a0011"), connection.writes)
        assertTrue(device.fields.values.none { it is FieldReading.Loading })
        assertTrue(connection.writeTimes.zipWithNext().all { (before, after) -> after - before >= 100 })
        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.set(DeviceSetting.EQ, FieldValue.Text("eap"))).error.code)
        session.disconnect()
        assertTrue(connection.closed)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun rawI7FlagsWithoutMeaningfulReadingsNeverBecomeProtocolReady() = runTest {
        val connection = RecordedConnection(emptyMap(), repeatedNotifications = listOf("dd00040cffffffea aa".replace(" ", "")))
        val transport = RecordedTransport(connection)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30, i5WriteIntervalMillis = 0),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(Advertisement(DeviceId("i7"), manufacturerData = mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f))))
        runCurrent()

        val result = session.connect(DeviceId("i7"))

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertTrue(connection.closed)
    }

    @Test
    fun partialReadTimeoutKeepsConfirmedFieldsAndReportsTheUnavailableField() = runTest {
        val connection = RecordedConnection(i5Replies - "0d01")
        val transport = RecordedTransport(connection)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30, i5WriteIntervalMillis = 0),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))

        val device = requireNotNull(session.state.value.device)
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(FieldReading.Known(FieldValue.Number(100), "64"), device.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldReading.Loading, device.fields[DeviceField.FIRMWARE])
        assertEquals(ControlErrorCode.TIMEOUT, device.fieldErrors[DeviceField.FIRMWARE]?.code)
        assertEquals(ControlErrorCode.TIMEOUT, session.state.value.lastError?.code)
    }

    @Test
    fun remoteDisconnectAllowsAFreshConnectionAndRejectsOldCallbacks() = runTest {
        val first = RecordedConnection(i5Replies)
        val transport = RecordedTransport(first)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30, i5WriteIntervalMillis = 0),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        session.connect(i5Advertisement.deviceId)
        val firstId = requireNotNull(session.state.value.device).sessionId
        val latePhase = requireNotNull(transport.lastPhaseCallback)
        first.incoming.close(IllegalStateException("Device powered off"))
        runCurrent()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(null, session.state.value.device)
        assertEquals(ControlErrorCode.DISCONNECTED, session.state.value.lastError?.code)

        val second = RecordedConnection(i5Replies - "0d01")
        transport.connection = second
        assertEquals(OperationResult.Success, session.connect(i5Advertisement.deviceId))
        val beforeLate = session.state.value
        assertTrue(requireNotNull(beforeLate.device).sessionId > firstId)
        assertEquals(FieldReading.Loading, beforeLate.device.fields[DeviceField.FIRMWARE])
        first.deliverLate("09ff0000010d010e00010205002d".bytes())
        latePhase(ConnectionPhase.DISCOVERING)
        runCurrent()

        assertEquals(beforeLate, session.state.value)
        assertTrue(first.closed)
        assertEquals(false, second.closed)
        assertEquals(13, second.writes.size)
    }

    @Test
    fun concurrentEntrypointsShareOneConnectionAndUnknownNotificationsRemainObservable() = runTest {
        val connection = RecordedConnection(i5Replies)
        val transport = RecordedTransport(connection)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30, i5WriteIntervalMillis = 0),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val results = listOf(async { session.connect(i5Advertisement.deviceId) }, async { session.connect(i5Advertisement.deviceId) }).awaitAll()
        assertTrue(results.all { it == OperationResult.Success })
        assertEquals(13, connection.writes.size)

        // Synthetic unknown command and enum, distinct from the recorded initialization capture.
        connection.incoming.send("0000000000ffff".bytes())
        connection.incoming.send("000000000006030000a2".bytes())
        runCurrent()
        val device = requireNotNull(session.state.value.device)
        assertEquals(FieldReading.Unknown("a2"), device.fields[DeviceField.GAME_MODE])
        assertEquals(listOf("0000000000ffff", "000000000006030000a2"), device.unknownNotifications)
        assertTrue(device.rawNotifications.contains("0000000000ffff"))
    }

    @Test
    fun i7SmallMtuReadsReassembledMeaningfulStateWhileMissingFieldsStayUnavailable() = runTest {
        // Synthetic DD framing around independent research values. Split battery, then coalesced firmware+mode.
        val connection = RecordedConnection(emptyMap(), repeatedNotifications = listOf("dd00040ce4", "325053aa", "dd00040d010502f6aadd00020901020e01faaa"), now = { testScheduler.currentTime })
        val transport = RecordedTransport(connection)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(Advertisement(DeviceId("i7"), manufacturerData = mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f))))
        runCurrent()

        assertEquals(OperationResult.Success, session.connect(DeviceId("i7")))

        val device = requireNotNull(session.state.value.device)
        assertEquals(ModelProfile.I7_RS_V2, device.profile)
        assertEquals(FieldReading.Known(FieldValue.Number(100), "e4"), device.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldReading.Known(FieldValue.Number(80), "50"), device.fields[DeviceField.CASE_BATTERY])
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(device.fields[DeviceField.GAME_MODE]).value)
        assertEquals("152", assertIs<FieldValue.Firmware>(assertIs<FieldReading.Known>(device.fields[DeviceField.FIRMWARE]).value).displayText)
        assertEquals(4, connection.writes.size)
        assertTrue(connection.writes.all { it.length <= 40 })
        assertTrue(connection.writeTimes.zipWithNext().all { (before, after) -> after - before >= 100 })
        assertEquals(ControlErrorCode.TIMEOUT, device.fieldErrors[DeviceField.TOUCH_KEYS]?.code)
    }

    @Test
    fun disconnectCancelsPendingReadsAndNeverReplaysThemAfterTheDeadline() = runTest {
        val connection = RecordedConnection(emptyMap())
        val transport = RecordedTransport(connection)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val pending = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()
        assertEquals(ConnectionPhase.INITIALIZING, assertIs<ConnectionState.Connecting>(session.state.value.connection).phase)

        session.disconnect()
        runCurrent()
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(connection.closed)
        assertEquals(1, connection.writes.size)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(null, session.state.value.device)
    }

    @Test
    fun anUnresponsiveDeviceTimesOutWithoutClaimingProtocolReadiness() = runTest {
        val connection = RecordedConnection(emptyMap())
        val transport = RecordedTransport(connection)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 30, i5WriteIntervalMillis = 0),
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(session.connect(i5Advertisement.deviceId)).error.code)
        runCurrent()
        assertEquals(ControlErrorCode.TIMEOUT, session.state.value.lastError?.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertTrue(connection.closed)
    }

    @Test
    fun aTransportTooSmallForACompleteFrameIsExplicitlyUnsupported() = runTest {
        val connection = RecordedConnection(emptyMap(), maxWriteSize = 6)
        val transport = RecordedTransport(connection)
        val session = ControlSession(PlatformState(PermissionState.GRANTED, TransportAvailability.Available), transport = transport, scope = backgroundScope)
        session.startScan()
        runCurrent()
        transport.advertisements.emit(Advertisement(DeviceId("i7"), manufacturerData = mapOf(0xe5b8 to byteArrayOf(0x29, 0x0f))))
        runCurrent()

        assertEquals(ControlErrorCode.UNSUPPORTED, assertIs<OperationResult.Failure>(session.connect(DeviceId("i7"))).error.code)
        assertTrue(connection.writes.isEmpty())
        assertTrue(connection.closed)
    }

    @Test
    fun simultaneousScanEntrypointsShareTheScannerAndOldScanCallbacksAreIgnored() = runTest {
        val scans = mutableListOf<FlowCollector<Advertisement>>()
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = object : Flow<Advertisement> {
                override suspend fun collect(collector: FlowCollector<Advertisement>) {
                    scans += collector
                    awaitCancellation()
                }
            }
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = error("No connection needed")
        }
        val session = ControlSession(PlatformState(PermissionState.GRANTED, TransportAvailability.Available), transport = transport, scope = backgroundScope)
        listOf(async { session.startScan() }, async { session.startScan() }).awaitAll()
        assertEquals(1, scans.size)
        session.stopScan()
        session.startScan()
        scans.first().emit(Advertisement(DeviceId("stale"), "old scan"))
        scans.last().emit(Advertisement(DeviceId("fresh"), "new scan"))

        assertEquals(listOf(DeviceId("fresh")), session.state.value.candidates.map { it.deviceId })
    }

    @Test
    fun cancellingOneEntrypointDoesNotCancelTheSharedConnection() = runTest {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val openGate = CompletableDeferred<Unit>()
        val link = RecordedConnection(i5Replies)
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = advertisements
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
                onPhase(ConnectionPhase.DISCOVERING)
                openGate.await()
                return link
            }
        }
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )
        session.startScan()
        advertisements.emit(i5Advertisement)
        runCurrent()
        val firstEntrypoint = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()
        val secondEntrypoint = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()

        firstEntrypoint.cancelAndJoin()
        openGate.complete(Unit)

        assertEquals(OperationResult.Success, secondEntrypoint.await())
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(13, link.writes.size)
        session.disconnect()
    }

    @Test
    fun anOpenedLinkIsClosedWhenConnectionAcquisitionLosesToItsDeadline() = runTest {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val link = RecordedConnection(i5Replies)
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = advertisements
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = withContext(NonCancellable) {
                delay(20)
                link
            }
        }
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(connectTimeoutMillis = 10),
        )
        session.startScan()
        advertisements.emit(i5Advertisement)
        runCurrent()

        val result = session.connect(i5Advertisement.deviceId)

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(result).error.code)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertTrue(link.closed)
    }

    @Test
    fun repeatedAdvertisementsUpdateInPlaceWithoutChangingDiscoveryOrder() = runTest {
        val transport = RecordedTransport(RecordedConnection(emptyMap()))
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        transport.advertisements.emit(i5Advertisement.copy(deviceId = DeviceId("second")))
        transport.advertisements.emit(i5Advertisement.copy(name = "Updated name", rssi = -25))
        runCurrent()

        assertEquals(listOf(i5Advertisement.deviceId, DeviceId("second")), session.state.value.candidates.map { it.deviceId })
        assertEquals("Updated name", session.state.value.candidates.first().name)
        assertEquals(-25, session.state.value.candidates.first().rssi)
        session.stopScan()
    }

    @Test
    fun cancellingAddingFlowClosesItsUnfinishedLinkAndStopsInitialization() = runTest {
        val link = RecordedConnection(emptyMap())
        val transport = RecordedTransport(link)
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )
        session.startScan()
        runCurrent()
        transport.advertisements.emit(i5Advertisement)
        runCurrent()
        val adding = async { session.connectForAddDevice(i5Advertisement.deviceId) }
        runCurrent()
        assertEquals(ConnectionPhase.INITIALIZING, assertIs<ConnectionState.Connecting>(session.state.value.connection).phase)
        val writesBeforeCancel = link.writes.toList()

        adding.cancelAndJoin()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(null, session.state.value.device)
        assertTrue(link.closed)
        assertEquals(writesBeforeCancel, link.writes)
    }

    @Test
    fun cancellingAddingFlowDoesNotCancelAnExistingSharedConnection() = runTest {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        val openGate = CompletableDeferred<Unit>()
        val link = RecordedConnection(i5Replies)
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = advertisements
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
                onPhase(ConnectionPhase.DISCOVERING)
                openGate.await()
                return link
            }
        }
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )
        session.startScan()
        advertisements.emit(i5Advertisement)
        runCurrent()
        val original = async { session.connect(i5Advertisement.deviceId) }
        runCurrent()
        val adding = async { session.connectForAddDevice(i5Advertisement.deviceId) }
        runCurrent()

        adding.cancelAndJoin()
        openGate.complete(Unit)

        assertEquals(OperationResult.Success, original.await())
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(false, link.closed)
        session.disconnect()
    }

    private class RecordedTransport(var connection: RecordedConnection) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
        var lastPhaseCallback: ((ConnectionPhase) -> Unit)? = null
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
            lastPhaseCallback = onPhase
            onPhase(ConnectionPhase.DISCOVERING)
            return connection
        }
    }

    private class RecordedConnection(val replies: Map<String, String>, override val maxWriteSize: Int = 20, val repeatedNotifications: List<String> = emptyList(), val now: () -> Long = { 0L }) : ControlConnection {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var collector: FlowCollector<ByteArray>? = null
        override val notifications: Flow<ByteArray> = object : Flow<ByteArray> {
            override suspend fun collect(collector: FlowCollector<ByteArray>) {
                this@RecordedConnection.collector = collector
                incoming.receiveAsFlow().collect(collector)
            }
        }
        suspend fun deliverLate(bytes: ByteArray) {
            requireNotNull(collector).emit(bytes)
        }
        var subscribed = false
        var closed = false
        val writes = mutableListOf<String>()
        val writeTimes = mutableListOf<Long>()
        override suspend fun subscribe() {
            subscribed = true
        }
        override suspend fun write(bytes: ByteArray) {
            check(subscribed) { "The earbud requires notifications before its first read" }
            val hex = bytes.hex()
            writes += hex
            writeTimes += now()
            replies[hex.substring(10, 14)]?.let { incoming.send(it.bytes()) }
            repeatedNotifications.forEach { incoming.send(it.bytes()) }
        }
        override suspend fun close() {
            closed = true
            incoming.close()
        }
    }

    companion object {
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private val i5Advertisement = Advertisement(DeviceId("i5"), "name has no authority", -40, mapOf(0x5352 to byteArrayOf(0, 1, 0, 1)))

        // Independent real Linux RX capture, rose_reverse/analysis/i5_live_read_2026-10-18.jsonl.
        private val i5Replies = mapOf(
            "0101" to "09ff00000101011100000164620000ffe2",
            "0602" to "09ff00000106020e000000010020",
            "0201" to "09ff00000102010b000118",
            "0603" to "09ff00000106030b00011e",
            "0801" to "09ff00000108010b00011e",
            "0d01" to "09ff0000010d010e00010205002d",
            "0401" to "09ff00000104011200060502040605030746",
            "0a01" to "09ff0000010a010b00001f",
            "0605" to "09ff00000106050e000101010126",
            "0c01" to "09ff0000010c010b000122",
            "0607" to "09ff00000106070b000223",
            "0604" to "09ff00000106040b00001e",
            "0e03" to "09ff0000010e030b000126",
        )
    }
}

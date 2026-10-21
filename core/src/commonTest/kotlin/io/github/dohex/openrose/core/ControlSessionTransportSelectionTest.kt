package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.AudioProfileEvidence
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportFacts
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancelAndJoin

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionTransportSelectionTest {
    @Test
    fun candidatesExposeOnlyCompleteUsableI7ClassicAddresses() = runTest {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
        val transport = object : ControlTransport {
            override fun scan(): Flow<Advertisement> = advertisements
            override suspend fun connect(
                deviceId: DeviceId,
                endpoint: TransportEndpoint,
                onPhase: (ConnectionPhase) -> Unit,
            ): ControlConnection = error("This test only scans")
        }
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )
        val valid = Advertisement(DeviceId("i7-valid"), manufacturerData = i7Advertisement())
        val wrongFlag = Advertisement(DeviceId("i7-flag"), manufacturerData = i7Advertisement(flag = 0))
        val tooShort = Advertisement(DeviceId("i7-short"), manufacturerData = i7Advertisement(size = 14))
        val zeroAddress = Advertisement(DeviceId("i7-zero"), manufacturerData = i7Advertisement(address = ByteArray(6)))
        val broadcastAddress = Advertisement(DeviceId("i7-ff"), manufacturerData = i7Advertisement(address = ByteArray(6) { 0xff.toByte() }))
        val i5WithAddressBytes = Advertisement(
            DeviceId("i5"),
            manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1) + ByteArray(12) { it.toByte() }),
        )

        session.startScan()
        runCurrent()
        listOf(valid, wrongFlag, tooShort, zeroAddress, broadcastAddress, i5WithAddressBytes).forEach { advertisements.emit(it) }
        runCurrent()

        val candidates = session.state.value.candidates.associateBy { it.deviceId }
        assertEquals(ModelProfile.I7_RS_V2, candidates.getValue(valid.deviceId).profile)
        assertEquals("A1:B2:C3:D4:E5:F6", candidates.getValue(valid.deviceId).classicAddress)
        for (advertisement in listOf(wrongFlag, tooShort, zeroAddress, broadcastAddress, i5WithAddressBytes)) {
            assertEquals(null, candidates.getValue(advertisement.deviceId).classicAddress, advertisement.deviceId.value)
        }
        session.stopScan()
    }

    @Test
    fun classicI7UsesItsDedicatedSppEndpointForInitializationAndConfirmedSettings() = runTest {
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        )
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-classic"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)

        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        val expectedEndpoint = TransportEndpoint.SppRfcomm("A1:B2:C3:D4:E5:F6", sppUuid)
        assertEquals(listOf<TransportEndpoint>(expectedEndpoint), transport.endpoints)
        assertEquals(1, transport.factCalls)
        assertEquals("A1:B2:C3:D4:E5:F6", transport.lastFactsAddress)
        val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
        val reference = SessionReference(connected.deviceId, connected.sessionId)
        assertEquals("A1:B2:C3:D4:E5:F6", assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device.classicAddress)
        assertEquals(TransportSelection(expectedEndpoint, TransportSelectionReason.CLASSIC_AUDIO), session.state.value.transportSelection)
        assertEquals(2, transport.link.writes.size)
        assertEquals(FieldValue.Number(100), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.LEFT_BATTERY)).value)
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.GAME_MODE)).value)

        transport.link.initializing = false
        val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(false)) }
        runCurrent()
        assertEquals(listOf("ff02020e0011aa", "ff0302fa0e0caa"), transport.link.writes.takeLast(2))
        assertFalse(pending.isCompleted)
        val gameReply = "dd00020e00edaa".bytes()
        transport.link.incoming.send(gameReply.copyOfRange(0, 3))
        runCurrent()
        assertFalse(pending.isCompleted)
        transport.link.incoming.send(gameReply.copyOfRange(3, gameReply.size))
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        session.disconnect()
        assertEquals(null, session.state.value.transportSelection)
    }

    @Test
    fun fallbackReasonsUseOnlyAvailableModelAddressPlatformAndAudioEvidence() = runTest {
        val i7 = Advertisement(DeviceId("i7"), manufacturerData = i7Advertisement())
        val i7WithoutAddress = Advertisement(DeviceId("i7-no-address"), manufacturerData = i7Advertisement(flag = 0))
        val i5 = Advertisement(DeviceId("i5"), manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1)))
        val scenarios = listOf(
            RouteScenario(i7, TransportFacts(sppAvailable = false, audioProfile = AudioProfileEvidence.CLASSIC), false,
                TransportSelectionReason.SPP_UNAVAILABLE, 1),
            RouteScenario(i7WithoutAddress, TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC), false,
                TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE, 0),
            RouteScenario(i7, TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.UNKNOWN), false,
                TransportSelectionReason.AUDIO_PROFILE_UNKNOWN, 1),
            RouteScenario(i7, TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.LE_AUDIO), false,
                TransportSelectionReason.LE_AUDIO, 1),
            RouteScenario(i7, TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC), true,
                TransportSelectionReason.FORCED_BLE, 0),
            RouteScenario(i5, TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC), false,
                TransportSelectionReason.MODEL_BLE_ONLY, 0),
        )
        for ((index, scenario) in scenarios.withIndex()) {
            val link = if (scenario.advertisement.deviceId == i5.deviceId) {
                SelectionConnection(maxWriteSize = 20, queryReplies = mapOf("0101" to listOf("09ff00000101011100000164620000ffe2")))
            } else SelectionConnection(maxWriteSize = 512)
            val transport = SelectionTransport(scenario.facts, link)
            val session = session(transport, UserSettings(forceBle = scenario.forceBle))
            advertise(session, transport, scenario.advertisement)

            assertEquals(OperationResult.Success, session.connect(scenario.advertisement.deviceId), "scenario=$index")
            val profile = requireNotNull(session.state.value.candidates.single().profile)
            val ble = TransportEndpoint.BleGatt(profile.gatt.serviceUuid, profile.gatt.writeUuid, profile.gatt.notifyUuid)
            assertEquals(listOf<TransportEndpoint>(ble), transport.endpoints, "scenario=$index")
            assertEquals(TransportSelection(ble, scenario.reason), session.state.value.transportSelection, "scenario=$index")
            assertEquals(scenario.expectedFactsCalls, transport.factCalls, "scenario=$index")
            val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
            val reference = SessionReference(connected.deviceId, connected.sessionId)
            assertEquals(session.state.value.candidates.single().classicAddress,
                assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).device.classicAddress, "scenario=$index")
            session.disconnect()
        }
    }

    @Test
    fun leAudioKeepsBleAndSmallCapacityStillInitializesAndConfirmsTheSameSetting() = runTest {
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.LE_AUDIO),
            link = SelectionConnection(maxWriteSize = 10),
        )
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-le"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)

        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        val profile = ModelProfile.I7_RS_V2
        val ble = TransportEndpoint.BleGatt(profile.gatt.serviceUuid, profile.gatt.writeUuid, profile.gatt.notifyUuid)
        assertEquals(listOf<TransportEndpoint>(ble), transport.endpoints)
        assertEquals(TransportSelection(ble, TransportSelectionReason.LE_AUDIO), session.state.value.transportSelection)
        assertTrue(transport.link.writes.isNotEmpty())
        assertTrue(transport.link.writes.all { it.length / 2 <= 10 })
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(session.state.value.device?.fields?.get(DeviceField.GAME_MODE)).value)

        transport.link.initializing = false
        val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(false)) }
        runCurrent()
        assertEquals(listOf("ff09020e0018aa", "ff0a02fa0e13aa"), transport.link.writes.takeLast(2))
        val gameReply = "dd00020e00edaa".bytes()
        transport.link.incoming.send(gameReply.copyOfRange(0, 3))
        runCurrent()
        assertFalse(pending.isCompleted)
        transport.link.incoming.send(gameReply.copyOfRange(3, gameReply.size))
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        session.disconnect()
    }

    @Test
    fun attemptKeepsItsForceBleSnapshotWhenThePreferenceChangesDuringFactsLookup() = runTest {
        val factsGate = CompletableDeferred<Unit>()
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        ).also { it.factsGate = factsGate }
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-snapshot"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        val pending = async { session.connect(advertisement.deviceId) }
        runCurrent()
        assertEquals(1, transport.factCalls)
        assertEquals(emptyList(), transport.endpoints)

        session.updateSettings(session.state.value.settings.copy(forceBle = true))
        factsGate.complete(Unit)
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        val spp = TransportEndpoint.SppRfcomm("A1:B2:C3:D4:E5:F6", sppUuid)
        assertEquals(listOf<TransportEndpoint>(spp), transport.endpoints)
        assertEquals(true, session.state.value.settings.forceBle)
        assertEquals(TransportSelection(spp, TransportSelectionReason.CLASSIC_AUDIO), session.state.value.transportSelection)

        val writesBeforeChange = transport.link.writes.toList()
        session.updateSettings(session.state.value.settings.copy(forceBle = false))
        runCurrent()
        assertEquals(listOf<TransportEndpoint>(spp), transport.endpoints)
        assertEquals(writesBeforeChange, transport.link.writes)
        assertEquals(TransportSelection(spp, TransportSelectionReason.CLASSIC_AUDIO), session.state.value.transportSelection)
        session.disconnect()
    }

    @Test
    fun cancellingOneConnectCallerDoesNotCancelTheSessionOwnedFactsLookup() = runTest {
        val factsGate = CompletableDeferred<Unit>()
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        ).also { it.factsGate = factsGate }
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-caller-cancel"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)

        val first = async { session.connect(advertisement.deviceId) }
        runCurrent()
        assertEquals(1, transport.factCalls)
        first.cancelAndJoin()
        val second = async { session.connect(advertisement.deviceId) }
        runCurrent()
        assertEquals(1, transport.factCalls)

        factsGate.complete(Unit)
        runCurrent()
        assertEquals(OperationResult.Success, second.await())
        assertEquals(1, transport.endpoints.size)
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        session.disconnect()
    }

    @Test
    fun disconnectCancelsALateFactsResultBeforeAnyTransportConnection() = runTest {
        val factsGate = CompletableDeferred<Unit>()
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        ).also { it.factsGate = factsGate }
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-late-facts"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        val pending = async { session.connect(advertisement.deviceId) }
        runCurrent()

        session.disconnect()
        runCurrent()
        factsGate.complete(Unit)
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(true, transport.factsCancelled)
        assertEquals(emptyList(), transport.endpoints)
        assertEquals(null, session.state.value.transportSelection)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun factsLookupDeadlineEndsTheAttemptWithoutStartingEitherLink() = runTest {
        val factsGate = CompletableDeferred<Unit>()
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        ).also { it.factsGate = factsGate }
        val session = session(transport, timing = SessionTiming(connectTimeoutMillis = 50, queryTimeoutMillis = 10))
        val advertisement = Advertisement(DeviceId("i7-facts-timeout"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        val pending = async { session.connect(advertisement.deviceId) }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(true, transport.factsCancelled)
        assertEquals(emptyList(), transport.endpoints)
        assertEquals(null, session.state.value.transportSelection)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun noMeaningfulInitializationResponseClearsThePublishedSelection() = runTest {
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = false),
            link = SelectionConnection(maxWriteSize = 512).also { it.initializing = false },
        ).also { it.connectGate = CompletableDeferred() }
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-no-state"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        val pending = async { session.connect(advertisement.deviceId) }
        runCurrent()
        val ble = TransportEndpoint.BleGatt(ModelProfile.I7_RS_V2.gatt.serviceUuid,
            ModelProfile.I7_RS_V2.gatt.writeUuid, ModelProfile.I7_RS_V2.gatt.notifyUuid)
        assertEquals(TransportSelection(ble, TransportSelectionReason.SPP_UNAVAILABLE), session.state.value.transportSelection)
        transport.connectGate?.complete(Unit)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(null, session.state.value.transportSelection)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun failedSppAttemptDoesNotFallbackToASecondBleConnection() = runTest {
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        ).also { it.connectFailure = IllegalStateException("Synthetic SPP failure") }
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-spp-failure"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)

        assertEquals(ControlErrorCode.DISCONNECTED,
            assertIs<OperationResult.Failure>(session.connect(advertisement.deviceId)).error.code)
        assertEquals(listOf<TransportEndpoint>(TransportEndpoint.SppRfcomm("A1:B2:C3:D4:E5:F6", sppUuid)), transport.endpoints)
        assertEquals(null, session.state.value.transportSelection)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun bleReadbackTimeoutEndsOnlyThatSessionAndClearsItsSelection() = runTest {
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.LE_AUDIO),
            link = SelectionConnection(maxWriteSize = 10),
        )
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-ble-timeout"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        transport.link.initializing = false

        val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(false)) }
        runCurrent()
        val writesAfterQuery = transport.link.writes.size
        assertEquals(TransportSelectionReason.LE_AUDIO, session.state.value.transportSelection?.reason)
        advanceTimeBy(11)
        runCurrent()

        assertEquals(ControlErrorCode.TIMEOUT, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(SettingStage.TIMED_OUT, session.state.value.settingOperations.single().stage)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(null, session.state.value.transportSelection)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(writesAfterQuery, transport.link.writes.size)
        assertEquals(1, transport.endpoints.size)
    }

    @Test
    fun sppRemoteDisconnectPreservesSharedSessionFailureSemanticsAndClearsSelection() = runTest {
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = SelectionConnection(maxWriteSize = 512),
        )
        val session = session(transport)
        val advertisement = Advertisement(DeviceId("i7-spp-disconnect"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        transport.link.initializing = false

        val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(false)) }
        runCurrent()
        assertFalse(pending.isCompleted)
        transport.link.incoming.close(IllegalStateException("Synthetic SPP stream ended"))
        runCurrent()

        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(SettingStage.DISCONNECTED, session.state.value.settingOperations.single().stage)
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(null, session.state.value.transportSelection)
        assertEquals(1, transport.endpoints.size)
    }

    @Test
    fun sppEndpointUsesTheSharedHundredMillisecondWritePacing() = runTest {
        val link = SelectionConnection(maxWriteSize = 512, now = { testScheduler.currentTime })
        val transport = SelectionTransport(
            facts = TransportFacts(sppAvailable = true, audioProfile = AudioProfileEvidence.CLASSIC),
            link = link,
        )
        val session = session(transport, timing = SessionTiming(connectTimeoutMillis = 1_000, queryTimeoutMillis = 50, i7WriteIntervalMillis = 100))
        val advertisement = Advertisement(DeviceId("i7-spp-pacing"), manufacturerData = i7Advertisement())
        advertise(session, transport, advertisement)
        assertEquals(OperationResult.Success, session.connect(advertisement.deviceId))
        assertTrue(link.writeTimes.zipWithNext().all { (before, after) -> after - before >= 100 })

        link.initializing = false
        val pending = async { session.set(DeviceSetting.GAME_MODE, FieldValue.Toggle(false)) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("ff02020e0011aa", link.writes.last())
        advanceTimeBy(100)
        runCurrent()
        assertEquals("ff0302fa0e0caa", link.writes.last())
        link.incoming.send("dd00020e00edaa".bytes())
        runCurrent()
        assertEquals(OperationResult.Success, pending.await())
        assertTrue(link.writeTimes.zipWithNext().all { (before, after) -> after - before >= 100 })
        session.disconnect()
    }

    private suspend fun TestScope.advertise(
        session: ControlSession,
        transport: SelectionTransport,
        advertisement: Advertisement,
    ) {
        session.startScan()
        runCurrent()
        transport.advertisements.emit(advertisement)
        runCurrent()
    }

    private fun TestScope.session(
        transport: SelectionTransport,
        settings: UserSettings = UserSettings(),
        timing: SessionTiming = SessionTiming(connectTimeoutMillis = 1_000, queryTimeoutMillis = 10, i7WriteIntervalMillis = 0),
    ) = ControlSession(
        PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
        initialSettings = settings,
        transport = transport,
        scope = backgroundScope,
        timing = timing,
    )

    private class SelectionTransport(
        var facts: TransportFacts,
        val link: SelectionConnection,
    ) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
        var factCalls = 0
        var factsGate: CompletableDeferred<Unit>? = null
        var factsCancelled = false
        var lastFactsAddress: String? = null
        val endpoints = mutableListOf<TransportEndpoint>()
        var connectGate: CompletableDeferred<Unit>? = null
        var connectFailure: Exception? = null
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts {
            factCalls++
            lastFactsAddress = classicAddress
            try {
                factsGate?.await()
            } catch (cancelled: CancellationException) {
                factsCancelled = true
                throw cancelled
            }
            return facts
        }
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
            endpoints += endpoint
            connectGate?.await()
            connectFailure?.let { throw it }
            return link
        }
    }

    private class SelectionConnection(
        override val maxWriteSize: Int,
        private val queryReplies: Map<String, List<String>>? = null,
        private val now: () -> Long = { 0L },
    ) : ControlConnection {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        val writes = mutableListOf<String>()
        val writeTimes = mutableListOf<Long>()
        var initializing = true
        var subscribed = false
        override suspend fun subscribe() { subscribed = true }
        override suspend fun write(bytes: ByteArray) {
            check(subscribed)
            val hex = bytes.hex()
            writes += hex
            writeTimes += now()
            if (initializing) {
                if (queryReplies != null) {
                    val key = hex.takeIf { it.startsWith("08ee") }?.substring(10, 14)
                    key?.let { queryReplies[it] }?.forEach { incoming.send(it.bytes()) }
                } else {
                    incoming.send("dd00040ce4".bytes())
                    incoming.send("325053aa".bytes())
                    incoming.send("dd00040d010502f6aa".bytes())
                    incoming.send("dd00020901020e01faaa".bytes())
                }
            }
        }
        override suspend fun close() { incoming.close() }
    }

    private fun i7Advertisement(
        flag: Int = 1,
        address: ByteArray = byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte()),
        size: Int = 15,
    ): Map<Int, ByteArray> {
        val payload = ByteArray(size)
        payload[0] = 0x29
        payload[1] = 0x0f
        if (size > 5) payload[5] = flag.toByte()
        if (size >= 15) address.copyInto(payload, destinationOffset = 9)
        return mapOf(0xe5b8 to payload)
    }

    companion object {
        private const val sppUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private data class RouteScenario(
        val advertisement: Advertisement,
        val facts: TransportFacts,
        val forceBle: Boolean,
        val reason: TransportSelectionReason,
        val expectedFactsCalls: Int,
    )
}

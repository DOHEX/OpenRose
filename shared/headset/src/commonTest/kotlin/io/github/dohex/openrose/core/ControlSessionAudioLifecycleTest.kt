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
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionAudioLifecycleTest {

    @Test
    fun autoConnectTriggeredWhenAudioConnectedBondedDeviceDetected() = runTest {
        val connection = TestAudioConnection(i5Replies)
        val bondedCandidate = DeviceCandidate(
            deviceId = DeviceId("bonded-i5"),
            name = "EarFree i5",
            rssi = null,
            profile = ModelProfile.I5_ZT,
            manufacturerDataHex = "",
            classicAddress = "11:22:33:44:55:66",
        )
        val transport = TestAudioTransport(
            connection = connection,
            bondedList = listOf(bondedCandidate),
        )

        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        // Session starts disconnected
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)

        // Audio connected event emits bonded ROSE device
        transport.audioDeviceFlow.emit(DeviceId("bonded-i5"))
        runCurrent()
        advanceUntilIdle()

        // Session should automatically connect to the device
        val conn = assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(DeviceId("bonded-i5"), conn.deviceId)
        assertEquals(ModelProfile.I5_ZT, conn.profile)
        assertEquals(DeviceId("bonded-i5"), session.state.value.device?.deviceId)
    }

    @Test
    fun manualControlRetryWhileTheSameI5HasAudioClosesItsNewLinkOnAudioDisconnect() = runTest {
        for (platform in listOf(HostPlatform.ANDROID, HostPlatform.LINUX)) {
            val id = DeviceId("bonded-i5")
            val paired = DeviceCandidate(id, "EarFree i5", null, ModelProfile.I5_ZT, "")
            val transport = TestAudioTransport(TestAudioConnection(i5Replies), listOf(paired))
            val session = ControlSession(
                PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Available, hostPlatform = platform),
                transport = transport,
                scope = backgroundScope,
                timing = SessionTiming(writeIntervalMillis = 0),
            )
            transport.audioDeviceFlow.emit(id)
            runCurrent()
            session.disconnect()
            val retry = TestAudioConnection(i5Replies)
            transport.connection = retry
            assertEquals(OperationResult.Success, session.connect(id))
            assertFalse(retry.closed)
            transport.audioDeviceFlow.emit(null)
            runCurrent()
            assertEquals(ConnectionState.Disconnected, session.state.value.connection, platform.name)
            assertTrue(retry.closed, platform.name)
        }
    }

    @Test
    fun losingAudioOnOneI5KeepsTheOtherManuallySelectedAndroidI5Connected() = runTest {
        val playing = DeviceCandidate(DeviceId("playing-i5"), "EarFree i5", null, ModelProfile.I5_ZT, "")
        val silent = playing.copy(deviceId = DeviceId("silent-i5"))
        val transport = TestAudioTransport(TestAudioConnection(i5Replies), listOf(playing, silent))
        val session = ControlSession(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available, hostPlatform = HostPlatform.ANDROID),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )
        transport.audioDeviceFlow.emit(playing.deviceId)
        runCurrent()
        val manual = TestAudioConnection(i5Replies)
        transport.connection = manual
        assertEquals(OperationResult.Success, session.connect(silent.deviceId))
        transport.audioDeviceFlow.emit(null)
        runCurrent()
        assertEquals(silent.deviceId, session.state.value.readySession?.deviceId)
        assertFalse(manual.closed)
    }

    @Test
    fun desktopNoiseModeCanBeConfirmedBetweenInitializationQueries() = runTest {
        val link = TestAudioConnection(i5Replies - "0602" - "0201")
        val (session, _) = startAudioI5(link)
        link.awaitWrite(NOISE_QUERY)
        assertEquals(null, session.state.value.noiseModeSession)
        assertIs<OperationResult.Failure>(session.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL)))

        link.report(i5Replies.getValue("0602"))
        val early = withTimeout(1_000) { session.state.first { it.noiseModeSession != null } }
        val modeSession = requireNotNull(early.noiseModeSession)
        assertEquals(100L, testScheduler.currentTime, "only the gap between the battery and noise queries is needed")
        link.awaitWrite("08ee00000002010a0003")
        assertEquals(null, session.state.value.readySession)
        assertIs<OperationResult.Failure>(session.set(HeadsetCommand.GameMode(true)))
        val initializationWrites = link.writes.toList()
        val pending = async { session.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL), modeSession.sessionId) }

        // The unanswered 0201 owns the link until its normal response deadline.
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(initializationWrites, link.writes)
        link.awaitWrite(NORMAL_MODE_WRITE)
        link.awaitWrite(NOISE_QUERY)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf(NORMAL_MODE_WRITE, NOISE_QUERY), link.writes.drop(initializationWrites.size))
        assertFalse(pending.isCompleted)
        assertEquals(FieldValue.Text("wind"), assertIs<FieldReading.Known>(session.state.value.device?.diagnostics?.fields?.get(DeviceField.NOISE_MODE)).value)
        link.report(NORMAL_MODE_REPLY)
        assertEquals(OperationResult.Success, pending.await())
        assertEquals(FieldValue.Text("normal"), assertIs<FieldReading.Known>(session.state.value.device?.diagnostics?.fields?.get(DeviceField.NOISE_MODE)).value)
        assertEquals(SettingStage.CONFIRMED, session.state.value.settingOperations.single().stage)
        assertEquals(null, session.state.value.readySession)

        val ready = withTimeout(5_000) { session.state.first { it.readySession != null } }
        assertEquals(modeSession, ready.readySession)
        assertEquals(15, link.writes.size, "all 13 initialization reads and the mode write/readback complete")
    }

    @Test
    fun earlyNoiseModeRequiresThisDesktopAudioSessionAndItsCurrentI5Mode() = runTest {
        val link = TestAudioConnection(i5Replies)
        val (session, _) = startAudioI5(link)
        link.awaitWrite("08ee00000002010a0003")
        val state = session.state.value
        val device = requireNotNull(state.device)
        val expected = SessionReference(device.deviceId, device.sessionId)
        assertEquals(expected, state.noiseModeSession)
        assertEquals(expected, state.copy(platform = state.platform.copy(hostPlatform = HostPlatform.WINDOWS)).noiseModeSession)
        val unknownMode = persistentMapOf<DeviceField, FieldReading>(DeviceField.NOISE_MODE to FieldReading.Unknown("ff"))
        for ((scenario, ineligible) in listOf(
            "unrelated audio" to state.copy(audioDeviceId = DeviceId("other-headset")),
            "Android" to state.copy(platform = state.platform.copy(hostPlatform = HostPlatform.ANDROID)),
            "non-i5 model" to state.copy(device = device.copy(profile = ModelProfile.I7_RS_V2)),
            "missing mode" to state.copy(device = device.copy(diagnostics = device.diagnostics.copy(fields = persistentMapOf()))),
            "unknown mode" to state.copy(device = device.copy(diagnostics = device.diagnostics.copy(fields = unknownMode))),
            "stale session" to state.copy(device = device.copy(sessionId = device.sessionId + 1)),
        )) {
            assertEquals(null, ineligible.noiseModeSession, scenario)
        }
    }

    @Test
    fun disconnectDuringEarlyReadbackRejectsTheOldRequestAfterSameI5Reconnects() = runTest {
        val old = TestAudioConnection(i5Replies - "0602")
        val (session, transport) = startAudioI5(old)
        old.awaitWrite(NOISE_QUERY)
        old.report(i5Replies.getValue("0602"))
        old.awaitWrite("08ee00000002010a0003")
        val reference = requireNotNull(session.state.value.noiseModeSession)
        val pending = async { session.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL), reference.sessionId) }
        old.awaitWrite(NORMAL_MODE_WRITE)
        old.awaitWrite(NOISE_QUERY)
        assertEquals(null, session.state.value.readySession)

        transport.audioDeviceFlow.emit(null)
        runCurrent()
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(pending.await()).error.code)
        assertEquals(null, session.state.value.noiseModeSession)
        val fresh = TestAudioConnection(i5Replies)
        transport.connection = fresh
        transport.audioDeviceFlow.emit(reference.deviceId)
        val replacement = withTimeout(5_000) { session.state.first { it.readySession != null } }
        assertTrue(requireNotNull(replacement.readySession).sessionId > reference.sessionId)
        old.report(NORMAL_MODE_REPLY)
        runCurrent()
        assertEquals(replacement, session.state.value)
        val stale = session.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL), reference.sessionId)
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(stale).error.code)
        assertEquals(13, fresh.writes.size)
    }

    @Test
    fun desktopI5GivesTheNextWriteSlotToANoiseCommandQueuedDuringTheGap() = runTest {
        val link = TestAudioConnection(i5Replies - "0101" - "0602", writeDurationMillis = 20)
        val (session, _) = startAudioI5(link)
        link.awaitWrite(BATTERY_QUERY)
        advanceTimeBy(70)
        link.report(i5Replies.getValue("0101"))
        link.awaitWrite(NOISE_QUERY)
        assertEquals(120L, testScheduler.currentTime, "battery write completed at 20 ms; its reply arrived at 70 ms")

        advanceTimeBy(50)
        link.report(i5Replies.getValue("0602"))
        val reference = requireNotNull(session.state.first { it.noiseModeSession != null }.noiseModeSession)
        advanceTimeBy(20)
        val change = async { session.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL), reference.sessionId) }
        link.awaitWrite(NORMAL_MODE_WRITE)
        assertEquals(240L, testScheduler.currentTime, "mode change takes the slot reserved by the 140 ms write completion")
        assertEquals(listOf(BATTERY_QUERY, NOISE_QUERY, NORMAL_MODE_WRITE), link.writes)
        link.report(NORMAL_MODE_REPLY)
        link.awaitWrite(NOISE_QUERY)
        assertEquals(360L, testScheduler.currentTime, "readback also respects the setting write's completion")
        advanceTimeBy(150)
        assertFalse(change.isCompleted, "an unsolicited report before the readback cannot confirm this request")
        link.report(NORMAL_MODE_REPLY)
        assertEquals(OperationResult.Success, change.await())
        link.awaitWrite("08ee00000002010a0003")
        assertEquals(510L, testScheduler.currentTime, "the full gap elapsed while awaiting readback")
    }

    @Test
    fun androidI5KeepsItsExistingPauseAfterTheBatteryReply() = runTest {
        val link = TestAudioConnection(i5Replies - "0101", writeDurationMillis = 20)
        startAudioI5(link, HostPlatform.ANDROID)
        link.awaitWrite(BATTERY_QUERY)
        advanceTimeBy(70)
        link.report(i5Replies.getValue("0101"))
        link.awaitWrite(NOISE_QUERY)
        assertEquals(170L, testScheduler.currentTime)
    }

    private suspend fun TestScope.startAudioI5(
        link: TestAudioConnection,
        hostPlatform: HostPlatform = HostPlatform.LINUX,
    ): Pair<ControlSession, TestAudioTransport> {
        val device = DeviceCandidate(DeviceId("desktop-i5"), "EarFree i5", null, ModelProfile.I5_ZT, "")
        val transport = TestAudioTransport(link, listOf(device))
        val session = ControlSession(
            PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Available, hostPlatform = hostPlatform),
            transport = transport,
            scope = backgroundScope,
            timeSource = testScheduler.timeSource,
        )
        transport.audioDeviceFlow.emit(device.deviceId)
        return session to transport
    }

    @Test
    fun autoDisconnectWhenSystemAudioDisconnects() = runTest {
        val connection = TestAudioConnection(i5Replies)
        val bondedCandidate = DeviceCandidate(
            deviceId = DeviceId("bonded-i5"),
            name = "EarFree i5",
            rssi = null,
            profile = ModelProfile.I5_ZT,
            manufacturerDataHex = "",
            classicAddress = "11:22:33:44:55:66",
        )
        val transport = TestAudioTransport(
            connection = connection,
            bondedList = listOf(bondedCandidate),
        )

        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        // Connect via audio event
        transport.audioDeviceFlow.emit(DeviceId("bonded-i5"))
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(session.state.value.connection)

        // System audio disconnects
        transport.audioDeviceFlow.emit(null)
        runCurrent()
        advanceUntilIdle()
        // Session should automatically disconnect
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(null, session.state.value.device)
        assertTrue(connection.closed)
    }

    @Test
    fun manualConnectionWorksWithoutAudioConnection() = runTest {
        val connection = TestAudioConnection(i5Replies)
        val manualCandidate = DeviceCandidate(
            deviceId = DeviceId("manual-i5"),
            name = "EarFree i5",
            rssi = -45,
            profile = ModelProfile.I5_ZT,
            manufacturerDataHex = "525300010001",
        )
        val transport = TestAudioTransport(
            connection = connection,
            bondedList = listOf(manualCandidate),
        )

        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        // Audio is not connected (null)
        assertEquals(null, transport.audioDeviceFlow.replayCache.firstOrNull())

        // Manual connect
        val result = session.connect(DeviceId("manual-i5"))
        runCurrent()
        assertEquals(OperationResult.Success, result)
        assertIs<ConnectionState.Connected>(session.state.value.connection)

        // When audio emits null, manual connection must NOT disconnect
        transport.audioDeviceFlow.emit(null)
        runCurrent()
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertFalse(connection.closed)

        // Manual disconnect works
        session.disconnect()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertTrue(connection.closed)
    }

    @Test
    fun nonRoseAudioDeviceDoesNotTriggerAutoConnect() = runTest {
        val connection = TestAudioConnection(i5Replies)
        val unrelatedBonded = DeviceCandidate(
            deviceId = DeviceId("car-audio"),
            name = "Car Kit Audio",
            rssi = null,
            profile = null,
            manufacturerDataHex = "",
        )
        val transport = TestAudioTransport(
            connection = connection,
            bondedList = listOf(unrelatedBonded),
        )

        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        transport.audioDeviceFlow.emit(DeviceId("car-audio"))
        runCurrent()
        advanceUntilIdle()

        // Session should remain disconnected
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
        assertEquals(0, connection.writes.size)
    }

    @Test
    fun manualDisconnectDoesNotReconnectWhileSameAudioRemains() = runTest {
        val connection = TestAudioConnection(i5Replies)
        val bondedCandidate = DeviceCandidate(
            deviceId = DeviceId("bonded-i5"),
            name = "EarFree i5",
            rssi = null,
            profile = ModelProfile.I5_ZT,
            manufacturerDataHex = "",
            classicAddress = "11:22:33:44:55:66",
        )
        val transport = TestAudioTransport(
            connection = connection,
            bondedList = listOf(bondedCandidate),
        )

        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        transport.audioDeviceFlow.emit(DeviceId("bonded-i5"))
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(session.state.value.connection)

        // User manually disconnects
        session.disconnect()
        runCurrent()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)

        // Audio connection remains unchanged at bonded-i5; session must stay disconnected
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, session.state.value.connection)
    }

    @Test
    fun modelProfileMatchRecognizesRoseDeviceNames() {
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("EarFree i5"))
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("ROSE i5"))
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("EARFREE I5"))

        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("EarFeel i7"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("ROSE i7"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("EARFEEL I7"))

        // The vendor renamed EarFree to EarFeel, so the model number decides before the brand word does.
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("EarFeel-i5"))
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("EarFeel i5"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("EarFree i7"))
        // Without a model number the brand word still decides, as before the rename.
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("EarFree"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("EarFeel"))

        assertEquals(null, ModelProfile.match("iPhone 15"))
        assertEquals(null, ModelProfile.match("ThinkPad i7"))
        assertEquals(null, ModelProfile.match("Sony WH-1000XM4"))
        assertEquals(null, ModelProfile.match("AirPods Pro"))
        assertEquals(null, ModelProfile.match(null))
        assertEquals(null, ModelProfile.match(""))
    }

    @Test
    fun nonRoseAudioDeviceConnectionDoesNotDisconnectActiveRoseSession() = runTest {
        val i5 = DeviceCandidate(DeviceId("i5-mac"), "EarFree i5", -50, ModelProfile.I5_ZT, "")
        val transport = TestAudioTransport(TestAudioConnection(i5Replies), bondedList = listOf(i5))
        val session = ControlSession(
            initialPlatformState = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        )

        // Auto-connects to i5
        transport.audioDeviceFlow.emit(DeviceId("i5-mac"))
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(session.state.value.connection)

        // Non-ROSE audio device connects (e.g. car bluetooth, external speaker)
        transport.audioDeviceFlow.emit(DeviceId("car-bluetooth"))
        runCurrent()
        advanceUntilIdle()
        // Active ROSE session must NOT be disconnected!
        assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(DeviceId("i5-mac"), (session.state.value.connection as ConnectionState.Connected).deviceId)
    }

    private class TestAudioTransport(
        var connection: TestAudioConnection,
        val bondedList: List<DeviceCandidate> = emptyList(),
    ) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 8)
        val audioDeviceFlow = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)

        override val audioConnectedDevice: Flow<DeviceId?> get() = audioDeviceFlow
        override suspend fun bondedDevices(): List<DeviceCandidate> = bondedList

        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
            onPhase(ConnectionPhase.DISCOVERING)
            return connection
        }
    }

    private class TestAudioConnection(
        val replies: Map<String, String>,
        override val maxWriteSize: Int = 20,
        private val writeDurationMillis: Long = 0,
    ) : ControlConnection {
        private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val notifications: Flow<ByteArray> = incoming
        var subscribed = false
        var closed = false
        val writes = mutableListOf<String>()
        private val lastWrite = MutableStateFlow<String?>(null)

        suspend fun awaitWrite(frame: String) {
            withTimeout(5_000) { lastWrite.first { it == frame } }
        }

        suspend fun report(hex: String) = incoming.emit(hex.bytes())

        override suspend fun subscribe() {
            subscribed = true
        }
        override suspend fun write(bytes: ByteArray) {
            check(subscribed) { "The earbud requires notifications before its first read" }
            val hex = bytes.hex()
            writes += hex
            lastWrite.value = hex
            delay(writeDurationMillis)
            replies[hex.substring(10, 14)]?.let { incoming.emit(it.bytes()) }
        }
        override suspend fun close() {
            closed = true
        }
    }

    companion object {
        private const val BATTERY_QUERY = "08ee00000001010a0002"
        private const val NOISE_QUERY = "08ee00000006020a0008"
        private const val NORMAL_MODE_WRITE = "08ee00000006820e00000100008d"
        private const val NORMAL_MODE_REPLY = "09ff00000106020e000001000020"
        private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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

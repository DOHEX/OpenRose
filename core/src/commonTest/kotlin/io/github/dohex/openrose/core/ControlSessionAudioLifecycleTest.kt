package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
            timing = SessionTiming(i5WriteIntervalMillis = 0),
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
            timing = SessionTiming(i5WriteIntervalMillis = 0),
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
            timing = SessionTiming(i5WriteIntervalMillis = 0),
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
    fun sessionContinuityAcrossStyleSwitches() = runTest {
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
            timing = SessionTiming(i5WriteIntervalMillis = 0),
        )

        // Auto connect
        transport.audioDeviceFlow.emit(DeviceId("bonded-i5"))
        runCurrent()
        advanceUntilIdle()
        val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
        val initialSessionId = connected.sessionId
        val writesCountBefore = connection.writes.size

        // Switch interface style from MIUIX to MATERIAL3
        assertEquals(InterfaceStyle.MIUIX, session.state.value.settings.interfaceStyle)
        session.updateSettings(session.state.value.settings.copy(interfaceStyle = InterfaceStyle.MATERIAL3))
        runCurrent()

        // Session must be continuous, no reconnect, no extra writes
        val stillConnected = assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(initialSessionId, stillConnected.sessionId)
        assertEquals(InterfaceStyle.MATERIAL3, session.state.value.settings.interfaceStyle)
        assertEquals(writesCountBefore, connection.writes.size)
        assertFalse(connection.closed)

        // Switch back to MIUIX
        session.updateSettings(session.state.value.settings.copy(interfaceStyle = InterfaceStyle.MIUIX))
        runCurrent()
        val stillConnected2 = assertIs<ConnectionState.Connected>(session.state.value.connection)
        assertEquals(initialSessionId, stillConnected2.sessionId)
        assertEquals(InterfaceStyle.MIUIX, session.state.value.settings.interfaceStyle)
        assertFalse(connection.closed)
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
            timing = SessionTiming(i5WriteIntervalMillis = 0),
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
            timing = SessionTiming(i5WriteIntervalMillis = 0),
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
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("i5"))
        assertEquals(ModelProfile.I5_ZT, ModelProfile.match("EARFREE I5"))

        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("EarFeel i7"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("ROSE i7"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("i7"))
        assertEquals(ModelProfile.I7_RS_V2, ModelProfile.match("EARFEEL I7"))

        assertEquals(null, ModelProfile.match("Sony WH-1000XM4"))
        assertEquals(null, ModelProfile.match("AirPods Pro"))
        assertEquals(null, ModelProfile.match(null))
        assertEquals(null, ModelProfile.match(""))
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
    ) : ControlConnection {
        private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val notifications: Flow<ByteArray> = incoming
        var subscribed = false
        var closed = false
        val writes = mutableListOf<String>()

        override suspend fun subscribe() { subscribed = true }
        override suspend fun write(bytes: ByteArray) {
            check(subscribed) { "The earbud requires notifications before its first read" }
            val hex = bytes.hex()
            writes += hex
            replies[hex.substring(10, 14)]?.let { incoming.emit(it.bytes()) }
        }
        override suspend fun close() { closed = true }
    }

    companion object {
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

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlSessionPresentationTest {
    @Test
    fun readySessionPublishesNormalizedStateAndClaimsOneConnectionPrompt() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport)
        val id = DeviceId("i5")
        connect(session, transport, id)

        val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
        val reference = SessionReference(id, connected.sessionId)
        val presentation = session.state.value.presentation
        assertEquals(reference, presentation.reference)
        assertEquals("i5", presentation.notificationTag)
        assertEquals(IslandMode.OFFICIAL, presentation.islandMode)
        assertTrue(presentation.focusNotificationEnabled)
        assertTrue(presentation.allowIslandFields)
        assertEquals(PresentationReading.Known(100), presentation.battery?.left)
        assertEquals(PresentationReading.Known(98), presentation.battery?.right)
        assertEquals(PresentationReading.NotProvided, presentation.battery?.case)
        assertEquals(PresentationReading.Known("wind"), presentation.noise?.mode)
        assertTrue(DeviceSetting.NOISE_MODE in presentation.actions)

        val prompt = assertNotNull(presentation.connectionPrompt)
        assertEquals(reference, prompt.reference)
        assertEquals(IslandMode.OFFICIAL, prompt.mode)
        assertFalse(prompt.claimed)
        assertEquals(5_000L, prompt.durationMillis)
        assertTrue(session.claimConnectionPrompt(reference, prompt.id))
        assertFalse(session.claimConnectionPrompt(reference, prompt.id))

        val snapshot = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertTrue(assertNotNull(snapshot.presentation.connectionPrompt).claimed)
        assertEquals("64", (assertIs<FieldReading.Known>(snapshot.device.fields[DeviceField.LEFT_BATTERY])).rawHex)
        assertEquals(PresentationReading.Known(100), snapshot.presentation.battery?.left)
        @Suppress("UNCHECKED_CAST")
        (snapshot.presentation.actions as MutableSet<DeviceSetting>).clear()
        assertTrue(DeviceSetting.NOISE_MODE in session.state.value.presentation.actions)

        val writesBeforeStyleChange = transport.connection.writes.size
        session.updateSettings(session.state.value.settings.copy(
            interfaceStyle = InterfaceStyle.MATERIAL3,
            appearance = Appearance.DARK,
            forceBle = true,
        ))
        assertEquals(prompt.id, session.state.value.presentation.connectionPrompt?.id)
        assertEquals(writesBeforeStyleChange, transport.connection.writes.size)

        val link = transport.connection
        link.deviceReply(i5Replies.getValue("0101").replace("6462", "6410"))
        runCurrent()
        val updated = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).presentation
        assertEquals(PresentationReading.Known(16), updated.battery?.right)
        assertEquals(prompt.id, updated.connectionPrompt?.id)
        assertTrue(assertNotNull(updated.connectionPrompt).claimed)
        assertFalse(session.claimConnectionPrompt(reference, prompt.id))

        session.disconnect()
        assertNull(session.state.value.presentation.reference)
        assertNull(session.state.value.presentation.connectionPrompt)
    }

    @Test
    fun theSixIslandAndFocusCombinationsKeepTheirOutputsIndependent() = runTest {
        val combinations = listOf(
            IslandMode.OFF to true,
            IslandMode.OFF to false,
            IslandMode.OFFICIAL to true,
            IslandMode.OFFICIAL to false,
            IslandMode.MODULE to true,
            IslandMode.MODULE to false,
        )
        for ((mode, focusEnabled) in combinations) {
            val transport = PresentationTransport(PresentationConnection(i5Replies))
            val settings = UserSettings(islandMode = mode, focusNotificationEnabled = focusEnabled)
            val session = newSession(transport, settings)
            val id = DeviceId("${mode.name.lowercase()}-$focusEnabled")
            connect(session, transport, id)

            val presentation = session.state.value.presentation
            assertEquals(focusEnabled, presentation.showStatusNotification, "$mode / focus=$focusEnabled")
            assertEquals(mode != IslandMode.OFF, presentation.allowIslandFields, "$mode / focus=$focusEnabled")
            assertEquals(mode != IslandMode.OFF, presentation.connectionPrompt != null, "$mode / focus=$focusEnabled")
            assertEquals(id.value, presentation.notificationTag)

            if (mode != IslandMode.OFF) {
                assertNotNull(presentation.connectionPrompt)
                val otherMode = if (mode == IslandMode.OFFICIAL) IslandMode.MODULE else IslandMode.OFFICIAL
                session.updateSettings(session.state.value.settings.copy(islandMode = otherMode))
                assertNull(session.state.value.presentation.connectionPrompt)
                session.updateSettings(session.state.value.settings.copy(islandMode = mode))
                assertNull(session.state.value.presentation.connectionPrompt)
            }

            session.updateSettings(session.state.value.settings.copy(focusNotificationEnabled = !focusEnabled))
            assertEquals(!focusEnabled, session.state.value.presentation.showStatusNotification)
            assertNull(session.state.value.presentation.connectionPrompt)
            session.disconnect()
        }
    }

    @Test
    fun anEmptyModuleTriggerSetDoesNotReplayTheConnectionAfterSettingsChange() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport, UserSettings(islandMode = IslandMode.MODULE, islandTriggers = emptySet()))
        connect(session, transport, DeviceId("i5"))

        assertNull(session.state.value.presentation.connectionPrompt)
        assertFalse(session.state.value.presentation.allowIslandFields)
        assertTrue(session.state.value.presentation.showStatusNotification)

        session.updateSettings(session.state.value.settings.copy(islandTriggers = setOf(IslandTrigger.CONNECTION)))
        assertNull(session.state.value.presentation.connectionPrompt)
        assertTrue(session.state.value.presentation.allowIslandFields)
        session.disconnect()
    }

    @Test
    fun anUnknownBatteryWaitsForARealReadingAndKeepsUnavailableValuesDistinct() = runTest {
        val unknownBattery = i5Replies.getValue("0101").replace("6462", "ffff")
        val transport = PresentationTransport(PresentationConnection(i5Replies + ("0101" to unknownBattery)))
        val session = newSession(transport)
        connect(session, transport, DeviceId("i5"))

        val initial = session.state.value.presentation
        assertNull(initial.connectionPrompt)
        assertEquals(PresentationReading.NotProvided, initial.battery?.left)
        assertEquals(PresentationReading.NotProvided, initial.battery?.right)

        transport.connection.deviceReply(i5Replies.getValue("0101").replace("64620000ff", "6462000000"))
        runCurrent()

        val updated = session.state.value.presentation
        assertEquals(PresentationReading.Known(100), updated.battery?.left)
        assertEquals(PresentationReading.Known(98), updated.battery?.right)
        assertEquals(PresentationReading.Unknown, updated.battery?.case)
        assertNotNull(updated.connectionPrompt)
        session.disconnect()
    }

    @Test
    fun anExpiredOldPromptTimerCannotClearTheNextSessionPrompt() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport, promptDurationMillis = 100)
        val id = DeviceId("i5")
        connect(session, transport, id)
        val first = assertNotNull(session.state.value.presentation.connectionPrompt)

        advanceTimeBy(40)
        runCurrent()
        session.disconnect()
        transport.connection = PresentationConnection(i5Replies)
        connect(session, transport, id)
        val second = assertNotNull(session.state.value.presentation.connectionPrompt)
        assertNotEquals(first.id, second.id)

        advanceTimeBy(60)
        runCurrent()
        assertEquals(second.id, session.state.value.presentation.connectionPrompt?.id)
        advanceTimeBy(40)
        runCurrent()
        assertNull(session.state.value.presentation.connectionPrompt)
        session.disconnect()
    }

    @Test
    fun sessionBoundSetRejectsAStaleNotificationWithoutWritingAndUsesTheCurrentQueue() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport)
        val id = DeviceId("i5")
        connect(session, transport, id)
        val firstConnection = assertIs<ConnectionState.Connected>(session.state.value.connection)
        val staleReference = SessionReference(id, firstConnection.sessionId)

        session.disconnect()
        transport.connection = PresentationConnection(i5Replies)
        connect(session, transport, id)
        val currentConnection = assertIs<ConnectionState.Connected>(session.state.value.connection)
        val currentReference = SessionReference(id, currentConnection.sessionId)
        val link = transport.connection
        val writesBeforeStaleSet = link.writes.size
        val beforeStaleSet = session.state.value

        val staleResult = assertIs<OperationResult.Failure>(
            session.set(staleReference, DeviceSetting.NOISE_MODE, FieldValue.Text("wind")),
        )
        assertEquals(ControlErrorCode.DISCONNECTED, staleResult.error.code)
        assertEquals(writesBeforeStaleSet, link.writes.size)
        assertEquals(beforeStaleSet, session.state.value, "A rejected old notification must not alter the current session")

        assertEquals(OperationResult.Success, session.set(currentReference, DeviceSetting.NOISE_MODE, FieldValue.Text("wind")))
        assertEquals(writesBeforeStaleSet + 2, link.writes.size)
        session.disconnect()
    }

    private fun kotlinx.coroutines.test.TestScope.newSession(
        transport: PresentationTransport,
        settings: UserSettings = UserSettings(),
        promptDurationMillis: Long = 5_000,
    ) = ControlSession(
        PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
        initialSettings = settings,
        transport = transport,
        scope = backgroundScope,
        timing = SessionTiming(i5WriteIntervalMillis = 0, connectionPromptDurationMillis = promptDurationMillis),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.connect(
        session: ControlSession,
        transport: PresentationTransport,
        id: DeviceId,
    ) {
        session.startScan()
        runCurrent()
        transport.advertisements.emit(Advertisement(id, "i5", manufacturerData = mapOf(0x5352 to byteArrayOf(0, 1, 0, 1))))
        runCurrent()
        assertEquals(OperationResult.Success, session.connect(id))
    }

    private class PresentationTransport(var connection: PresentationConnection) : ControlTransport {
        val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 1)
        override fun scan(): Flow<Advertisement> = advertisements
        override suspend fun connect(
            deviceId: DeviceId,
            endpoint: TransportEndpoint,
            onPhase: (ConnectionPhase) -> Unit,
        ): ControlConnection = connection
    }

    private class PresentationConnection(private val replies: Map<String, String>) : ControlConnection {
        override val maxWriteSize = 20
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        val writes = mutableListOf<String>()

        override suspend fun subscribe() = Unit

        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.toHex()
            writes += hex
            val key = hex.substring(10, 14)
            replies[key]?.let { incoming.send(it.fromHex()) }
        }

        suspend fun deviceReply(hex: String) = incoming.send(hex.fromHex())
        override suspend fun close() { incoming.close() }
    }

    private companion object {
        val i5Replies = mapOf(
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

private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

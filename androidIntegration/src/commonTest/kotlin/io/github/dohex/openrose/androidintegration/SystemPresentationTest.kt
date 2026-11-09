package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemPresentationTest {
    @Test
    fun readySessionPublishesNormalizedStateAndClaimsOneConnectionPrompt() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport)
        val id = DeviceId("i5")
        connect(session, transport, id)

        val connected = assertIs<ConnectionState.Connected>(session.state.value.connection)
        val reference = SessionReference(id, connected.sessionId)
        val presentation = session.presentation.value
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

        val prompt = assertNotNull(presentation.prompt)
        assertEquals(reference, prompt.reference)
        assertEquals(IslandMode.OFFICIAL, prompt.mode)
        assertFalse(prompt.claimed)
        assertEquals(5_000L, prompt.durationMillis)
        assertTrue(session.claimPrompt(reference, prompt.id))
        assertFalse(session.claimPrompt(reference, prompt.id))

        val snapshot = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertTrue(assertNotNull(snapshot.presentation.prompt).claimed)
        assertEquals("64", (assertIs<FieldReading.Known>(snapshot.device.fields[DeviceField.LEFT_BATTERY])).rawHex)
        assertEquals(PresentationReading.Known(100), snapshot.presentation.battery?.left)
        @Suppress("UNCHECKED_CAST")
        (snapshot.presentation.actions as MutableSet<DeviceSetting>).clear()
        assertTrue(DeviceSetting.NOISE_MODE in session.presentation.value.actions)

        val writesBeforeSettingsChange = transport.connection.writes.size
        session.updateSettings(session.state.value.settings.copy(forceBle = true))
        assertEquals(prompt.id, session.presentation.value.prompt?.id)
        assertEquals(writesBeforeSettingsChange, transport.connection.writes.size)

        val link = transport.connection
        link.deviceReply(i5Replies.getValue("0101").replace("6462", "6410"))
        runCurrent()
        val updated = assertIs<SessionSnapshotResult.Available>(session.readSession(reference)).presentation
        assertEquals(PresentationReading.Known(16), updated.battery?.right)
        assertEquals(prompt.id, updated.prompt?.id)
        assertTrue(assertNotNull(updated.prompt).claimed)
        assertFalse(session.claimPrompt(reference, prompt.id))

        session.disconnect()
        assertNull(session.presentation.value.reference)
        assertNull(session.presentation.value.prompt)
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
            val settings = IntegrationSettings(islandMode = mode, focusNotificationEnabled = focusEnabled)
            val session = newSession(transport, settings)
            val id = DeviceId("${mode.name.lowercase()}-$focusEnabled")
            connect(session, transport, id)

            val presentation = session.presentation.value
            assertEquals(focusEnabled, presentation.showStatusNotification, "$mode / focus=$focusEnabled")
            assertEquals(mode != IslandMode.OFF, presentation.allowIslandFields, "$mode / focus=$focusEnabled")
            assertEquals(
                mode == IslandMode.MODULE,
                presentation.statusSummaryIsland,
                "$mode / focus=$focusEnabled: official mode's connection prompt is its only island",
            )
            assertEquals(mode != IslandMode.OFF, presentation.prompt != null, "$mode / focus=$focusEnabled")
            assertEquals(id.value, presentation.notificationTag)

            if (mode != IslandMode.OFF) {
                assertNotNull(presentation.prompt)
                val otherMode = if (mode == IslandMode.OFFICIAL) IslandMode.MODULE else IslandMode.OFFICIAL
                session.updateIntegrationSettings(session.settings.copy(islandMode = otherMode))
                assertNull(session.presentation.value.prompt)
                session.updateIntegrationSettings(session.settings.copy(islandMode = mode))
                assertNull(session.presentation.value.prompt)
            }

            session.updateIntegrationSettings(session.settings.copy(focusNotificationEnabled = !focusEnabled))
            assertEquals(!focusEnabled, session.presentation.value.showStatusNotification)
            assertNull(session.presentation.value.prompt)
            session.disconnect()
        }
    }

    @Test
    fun anEmptyModuleTriggerSetDoesNotReplayTheConnectionAfterSettingsChange() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport, IntegrationSettings(islandMode = IslandMode.MODULE, islandTriggers = persistentSetOf()))
        connect(session, transport, DeviceId("i5"))

        assertNull(session.presentation.value.prompt)
        assertFalse(session.presentation.value.allowIslandFields)
        assertFalse(session.presentation.value.statusSummaryIsland)
        assertTrue(session.presentation.value.showStatusNotification)

        session.updateIntegrationSettings(session.settings.copy(islandTriggers = persistentSetOf(IslandTrigger.CONNECTION)))
        assertNull(session.presentation.value.prompt)
        assertTrue(session.presentation.value.allowIslandFields)
        assertTrue(session.presentation.value.statusSummaryIsland)
        session.disconnect()
    }

    @Test
    fun anUnknownBatteryWaitsForARealReadingAndKeepsUnavailableValuesDistinct() = runTest {
        val unknownBattery = i5Replies.getValue("0101").replace("6462", "ffff")
        val transport = PresentationTransport(PresentationConnection(i5Replies + ("0101" to unknownBattery)))
        val session = newSession(transport)
        connect(session, transport, DeviceId("i5"))

        val initial = session.presentation.value
        assertNull(initial.prompt)
        assertEquals(PresentationReading.NotProvided, initial.battery?.left)
        assertEquals(PresentationReading.NotProvided, initial.battery?.right)

        transport.connection.deviceReply(i5Replies.getValue("0101").replace("64620000ff", "6462000000"))
        runCurrent()

        val updated = session.presentation.value
        assertEquals(PresentationReading.Known(100), updated.battery?.left)
        assertEquals(PresentationReading.Known(98), updated.battery?.right)
        assertEquals(PresentationReading.Unknown, updated.battery?.case)
        assertNotNull(updated.prompt)
        session.disconnect()
    }

    @Test
    fun anExpiredOldPromptTimerCannotClearTheNextSessionPrompt() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport, promptDurationMillis = 100)
        val id = DeviceId("i5")
        connect(session, transport, id)
        val first = assertNotNull(session.presentation.value.prompt)

        advanceTimeBy(40)
        runCurrent()
        session.disconnect()
        transport.connection = PresentationConnection(i5Replies)
        connect(session, transport, id)
        val second = assertNotNull(session.presentation.value.prompt)
        assertNotEquals(first.id, second.id)

        advanceTimeBy(60)
        runCurrent()
        assertEquals(second.id, session.presentation.value.prompt?.id)
        advanceTimeBy(40)
        runCurrent()
        assertNull(session.presentation.value.prompt)
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
        val beforeStaleSet = session.state.value to session.presentation.value

        val staleResult = assertIs<OperationResult.Failure>(
            session.set(staleReference, DeviceSetting.NOISE_MODE, FieldValue.Text("wind")),
        )
        assertEquals(ControlErrorCode.DISCONNECTED, staleResult.error.code)
        assertEquals(writesBeforeStaleSet, link.writes.size)
        assertEquals(beforeStaleSet, session.state.value to session.presentation.value, "A rejected old notification must not alter the current session")

        assertEquals(OperationResult.Success, session.set(currentReference, DeviceSetting.NOISE_MODE, FieldValue.Text("wind")))
        assertEquals(writesBeforeStaleSet + 2, link.writes.size)
        session.disconnect()
    }

    private fun kotlinx.coroutines.test.TestScope.newSession(
        transport: PresentationTransport,
        settings: IntegrationSettings = IntegrationSettings(),
        promptDurationMillis: Long = 5_000,
    ) = SystemEntryRuntime(
        LocalControlRuntime(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(writeIntervalMillis = 0),
        ),
        backgroundScope,
        PresentationTiming(promptDurationMillis = promptDurationMillis),
        initialIntegrationSettings = settings,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.connect(
        session: SystemEntryRuntime,
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
        override suspend fun close() {
            incoming.close()
        }
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

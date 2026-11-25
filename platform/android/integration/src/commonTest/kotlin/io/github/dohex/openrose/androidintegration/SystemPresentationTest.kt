package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
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
        assertEquals(IslandMode.SYSTEM_STYLE, presentation.islandMode)
        assertTrue(presentation.statusNotificationEnabled)
        assertTrue(presentation.allowIslandFields)
        assertEquals(PresentationReading.Known(100), presentation.battery?.left)
        assertEquals(PresentationReading.Known(98), presentation.battery?.right)
        assertEquals(PresentationReading.NotProvided, presentation.battery?.case)
        assertEquals(PresentationReading.Known("wind"), presentation.noise?.mode)
        assertTrue(DeviceSetting.NOISE_MODE in presentation.actions)

        val prompt = assertNotNull(presentation.prompt)
        assertEquals(reference, prompt.reference)
        assertEquals(IslandMode.SYSTEM_STYLE, prompt.mode)
        assertFalse(prompt.claimed)
        assertEquals(5_000L, prompt.durationMillis)
        assertTrue(session.claimPrompt(reference, prompt.id))
        assertFalse(session.claimPrompt(reference, prompt.id))

        val snapshot = assertIs<SessionSnapshotResult.Available>(session.readSession(reference))
        assertTrue(assertNotNull(snapshot.presentation.prompt).claimed)
        assertEquals("64", (assertIs<FieldReading.Known>(snapshot.device.diagnostics.fields[DeviceField.LEFT_BATTERY])).rawHex)
        assertEquals(PresentationReading.Known(100), snapshot.presentation.battery?.left)

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
    fun theSixIslandAndStatusNotificationCombinationsKeepTheirOutputsIndependent() = runTest {
        val combinations = listOf(
            IslandMode.OFF to true,
            IslandMode.OFF to false,
            IslandMode.SYSTEM_STYLE to true,
            IslandMode.SYSTEM_STYLE to false,
            IslandMode.OPENROSE_STYLE to true,
            IslandMode.OPENROSE_STYLE to false,
        )
        for ((mode, statusEnabled) in combinations) {
            val transport = PresentationTransport(PresentationConnection(i5Replies))
            val settings = IntegrationSettings(islandMode = mode, statusNotificationEnabled = statusEnabled)
            val session = newSession(transport, settings)
            val id = DeviceId("${mode.name.lowercase()}-$statusEnabled")
            connect(session, transport, id)

            val presentation = session.presentation.value
            assertEquals(statusEnabled, presentation.showStatusNotification, "$mode / status=$statusEnabled")
            assertEquals(mode != IslandMode.OFF, presentation.allowIslandFields, "$mode / status=$statusEnabled")
            assertEquals(
                mode == IslandMode.OPENROSE_STYLE,
                presentation.statusSummaryIsland,
                "$mode / status=$statusEnabled: system style's connection prompt is its only island",
            )
            assertEquals(mode != IslandMode.OFF, presentation.prompt != null, "$mode / status=$statusEnabled")
            assertEquals(id.value, presentation.notificationTag)

            if (mode != IslandMode.OFF) {
                assertNotNull(presentation.prompt)
                val otherMode = if (mode == IslandMode.SYSTEM_STYLE) IslandMode.OPENROSE_STYLE else IslandMode.SYSTEM_STYLE
                session.updateIntegrationSettings(session.settings.copy(islandMode = otherMode))
                assertNull(session.presentation.value.prompt)
                session.updateIntegrationSettings(session.settings.copy(islandMode = mode))
                assertNull(session.presentation.value.prompt)
            }

            session.updateIntegrationSettings(session.settings.copy(statusNotificationEnabled = !statusEnabled))
            assertEquals(!statusEnabled, session.presentation.value.showStatusNotification)
            assertNull(session.presentation.value.prompt)
            session.disconnect()
        }
    }

    @Test
    fun anEmptyOpenRoseTimingSetKeepsTheSummaryIslandWithoutReplayingTheConnection() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport, IntegrationSettings(islandMode = IslandMode.OPENROSE_STYLE, islandTimings = persistentSetOf()))
        connect(session, transport, DeviceId("i5"))

        assertNull(session.presentation.value.prompt)
        assertTrue(session.presentation.value.allowIslandFields)
        assertTrue(session.presentation.value.statusSummaryIsland, "the summary island stays without any display timing")
        assertTrue(session.presentation.value.showStatusNotification)

        session.updateIntegrationSettings(session.settings.copy(islandTimings = persistentSetOf(IslandTiming.CONNECTION)))
        assertNull(session.presentation.value.prompt, "the already-connected session does not replay its connection")
        assertTrue(session.presentation.value.statusSummaryIsland)

        session.updateIntegrationSettings(session.settings.copy(islandTimings = persistentSetOf(), islandSummaryEnabled = false))
        assertFalse(session.presentation.value.allowIslandFields, "no timing and no summary leaves OpenRose style without an island")
        assertFalse(session.presentation.value.statusSummaryIsland)
        session.disconnect()
    }

    @Test
    fun turningOffTheSummaryIslandKeepsTheOpenRosePromptButDropsThePersistentIsland() = runTest {
        val transport = PresentationTransport(PresentationConnection(i5Replies))
        val session = newSession(transport, IntegrationSettings(islandMode = IslandMode.OPENROSE_STYLE, islandSummaryEnabled = false))
        connect(session, transport, DeviceId("i5"))

        val presentation = session.presentation.value
        assertNotNull(presentation.prompt, "the connection prompt still shows as its own timed island")
        assertTrue(presentation.allowIslandFields)
        assertTrue(presentation.showStatusNotification)
        assertFalse(presentation.statusSummaryIsland)

        session.updateIntegrationSettings(session.settings.copy(islandSummaryEnabled = true))
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
            session.set(staleReference, HeadsetCommand.NoiseMode(NoiseMode.WIND)),
        )
        assertEquals(ControlErrorCode.DISCONNECTED, staleResult.error.code)
        assertEquals(writesBeforeStaleSet, link.writes.size)
        assertEquals(beforeStaleSet, session.state.value to session.presentation.value, "A rejected old notification must not alter the current session")

        assertEquals(OperationResult.Success, session.set(currentReference, HeadsetCommand.NoiseMode(NoiseMode.WIND)))
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
            val hex = bytes.hex()
            writes += hex
            val key = hex.substring(10, 14)
            replies[key]?.let { incoming.send(it.bytes()) }
        }

        suspend fun deviceReply(hex: String) = incoming.send(hex.bytes())
        override suspend fun close() {
            incoming.close()
        }
    }
}

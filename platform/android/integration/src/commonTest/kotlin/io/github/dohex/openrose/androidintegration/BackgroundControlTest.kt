package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ordinary background control is an intent the user saves plus platform facts about the service
 * that carries it. These tests observe both only through the unified control runtime.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackgroundControlTest {
    @Test
    fun missingPreferenceNeverEnablesBackgroundControlAndKeepsEveryExistingPreference() {
        val upgraded = HostSettingsCodec.decode(
            mapOf(
                "androidIntegration.island_mode" to "module",
                "androidIntegration.focus_notification_enabled" to "false",
                "core.force_ble" to "true",
                "androidIntegration.island_triggers" to "",
                "androidIntegration.fusion_settings_destination" to "openrose",
            ),
        )

        assertFalse(upgraded.integration.backgroundControlEnabled)
        assertEquals(
            HostSettings(
                control = UserSettings(forceBle = true),
                integration = IntegrationSettings(
                    islandMode = IslandMode.MODULE,
                    focusNotificationEnabled = false,
                    islandTriggers = persistentSetOf(),
                    fusionSettingsDestination = FusionSettingsDestination.OPENROSE,
                ),
            ),
            upgraded,
        )
        assertFalse(IntegrationSettingsCodec.decode(mapOf("androidIntegration.background_control_enabled" to "yes")).backgroundControlEnabled)
        assertFalse(IntegrationSettingsCodec.decode(mapOf("androidIntegration.background_control_enabled" to "")).backgroundControlEnabled)
    }

    @Test
    fun theEnabledIntentSurvivesPersistenceAndRebuildingTheRuntime() = runTest {
        val enabled = IntegrationSettings(backgroundControlEnabled = true)
        val restored = IntegrationSettingsCodec.decode(IntegrationSettingsCodec.encode(enabled))
        assertEquals("true", IntegrationSettingsCodec.encode(enabled)["androidIntegration.background_control_enabled"])
        assertEquals("false", IntegrationSettingsCodec.encode(IntegrationSettings())["androidIntegration.background_control_enabled"])

        val runtime = newRuntime(RoseTransport(), settings = restored)

        assertTrue(runtime.settings.backgroundControlEnabled)
    }

    @Test
    fun savingTheIntentIsNotReadinessUntilTheServiceReportsItIsRunning() = runTest {
        val runtime = newRuntime(RoseTransport())
        assertEquals(BackgroundControlStatus.Disabled, status(runtime))

        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = true))
        // Preference saved, nothing running yet: the host still has to start the service.
        assertEquals(BackgroundControlStatus.NotRunning, status(runtime))

        runtime.updatePlatformState(androidPlatform())
        assertEquals(BackgroundControlStatus.Running(notificationVisible = true), status(runtime, BackgroundServiceFact.Running))

        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
        runtime.updatePlatformState(androidPlatform())
        assertEquals(BackgroundControlStatus.Disabled, status(runtime, BackgroundServiceFact.NotRunning))
    }

    @Test
    fun aMissingBluetoothPermissionIsReportedAsTheReasonNeverAsReady() = runTest {
        for (permission in listOf(PermissionState.REQUIRED, PermissionState.DENIED)) {
            val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
            runtime.updatePlatformState(androidPlatform(permission = permission))

            assertEquals(
                BackgroundControlStatus.Blocked(BackgroundControlBlock.BLUETOOTH_PERMISSION),
                status(runtime, BackgroundServiceFact.NotRunning),
            )
        }

        // Granting the permission later makes the pending start possible again.
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform(permission = PermissionState.DENIED))
        runtime.updatePlatformState(androidPlatform(permission = PermissionState.GRANTED))
        assertEquals(BackgroundControlStatus.NotRunning, status(runtime))
    }

    @Test
    fun aRejectedServiceStartIsReportedWithItsReason() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))

        runtime.updatePlatformState(androidPlatform())

        assertEquals(
            BackgroundControlStatus.Blocked(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
            status(runtime, BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)),
        )
    }

    @Test
    fun aHiddenRunningNotificationIsDisclosedWhileControlStillRuns() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))

        runtime.updatePlatformState(androidPlatform())

        assertEquals(
            BackgroundControlStatus.Running(notificationVisible = false),
            status(runtime, BackgroundServiceFact.Running, RunningNotification.HIDDEN),
        )
    }

    @Test
    fun desktopHostsHaveNoBackgroundControl() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundControlStatus.Unsupported, status(runtime))
    }

    @Test
    fun focusNotificationAndIslandPresentationDoNotChangeBackgroundControl() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform())
        val running = status(runtime, BackgroundServiceFact.Running)

        for (islandMode in IslandMode.entries) {
            for (focus in listOf(false, true)) {
                runtime.updateIntegrationSettings(
                    runtime.settings.copy(islandMode = islandMode, focusNotificationEnabled = focus),
                )
                assertEquals(running, status(runtime, BackgroundServiceFact.Running))
                assertTrue(runtime.settings.backgroundControlEnabled)
            }
        }
        // And the reverse: turning background control off leaves the presentation choices alone.
        val presentation = runtime.settings.copy(backgroundControlEnabled = false)
        runtime.updateIntegrationSettings(presentation)
        assertEquals(IslandMode.MODULE, runtime.settings.islandMode)
        assertTrue(runtime.settings.focusNotificationEnabled)
    }

    @Test
    fun enablingDisablingAndRepeatingTheIntentNeverCreatesOrEndsAControlConnection() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport)
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        val before = assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        repeat(3) {
            runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = true))
            runtime.updatePlatformState(androidPlatform())
        }
        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
        runtime.updatePlatformState(androidPlatform())

        // The interface closing is no event at all for the runtime; the one session just stays.
        assertEquals(before, runtime.state.value.connection)
        assertEquals(1, transport.links.size)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    @Test
    fun audioDisconnectReleasesTheAutomaticConnectionButKeepsBackgroundControlOn() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform())

        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        transport.audioDevice.emit(null)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.single().closed)
        assertTrue(runtime.settings.backgroundControlEnabled)
        assertEquals(BackgroundControlStatus.Running(notificationVisible = true), status(runtime, BackgroundServiceFact.Running))

        // A later audio connection restores control in the same running owner.
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(2, transport.links.size)
        runtime.disconnect()
    }

    @Test
    fun disablingBackgroundControlDoesNotDisconnectAManualConnection() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, settings = IntegrationSettings(backgroundControlEnabled = true))
        assertEquals(OperationResult.Success, runtime.connect(rose.deviceId))

        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
        advanceUntilIdle()

        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    private fun rosePair() = DeviceCandidate(DeviceId("rose"), "EarFree i5", null, ModelProfile.I5_ZT, "", "11:22:33:44:55:66")

    private fun androidPlatform(permission: PermissionState = PermissionState.GRANTED) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
    )

    /** The background status the integration derives from the control state plus the service facts the platform reports. */
    private fun status(
        runtime: SystemEntryRuntime,
        service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
        notifications: RunningNotification = RunningNotification.VISIBLE,
    ): BackgroundControlStatus = IntegrationState(
        settings = runtime.settings,
        platform = IntegrationPlatformState(backgroundService = BackgroundServiceFacts(service, notifications)),
    ).backgroundControl(runtime.state.value)

    private fun TestScope.newRuntime(transport: RoseTransport, settings: IntegrationSettings = IntegrationSettings()): SystemEntryRuntime = SystemEntryRuntime(
        control = LocalControlRuntime(
            initialPlatformState = androidPlatform(),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
        ),
        scope = backgroundScope,
        initialIntegrationSettings = settings,
    )

    private class RoseTransport(private val bonded: List<DeviceCandidate> = emptyList()) : ControlTransport {
        val audioDevice = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
        val links = mutableListOf<RoseConnection>()

        override val audioConnectedDevice: Flow<DeviceId?> get() = audioDevice
        override suspend fun bondedDevices(): List<DeviceCandidate> = bonded
        override fun scan(): Flow<Advertisement> = emptyFlow()
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = RoseConnection().also(links::add)
    }

    private class RoseConnection : ControlConnection {
        override val maxWriteSize = 20
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications = incoming.receiveAsFlow()
        var closed = false

        override suspend fun subscribe() = Unit

        override suspend fun write(bytes: ByteArray) {
            val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            replies[hex.substring(10, 14)]?.let { incoming.send(it.chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray()) }
        }

        override suspend fun close() {
            closed = true
            incoming.close()
        }

        private companion object {
            val replies = mapOf(
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
}

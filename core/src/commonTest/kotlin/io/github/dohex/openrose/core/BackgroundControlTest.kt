package io.github.dohex.openrose.core

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
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Ordinary background control is an intent the user saves plus platform facts about the service
 * that carries it. These tests observe both only through the unified control runtime.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackgroundControlTest {
    @Test
    fun missingPreferenceNeverEnablesBackgroundControlAndKeepsEveryExistingPreference() {
        val upgraded = UserSettingsCodec.decode(
            mapOf(
                "interface_style" to "material3",
                "appearance" to "dark",
                "island_mode" to "module",
                "focus_notification_enabled" to "false",
                "force_ble" to "true",
                "island_triggers" to "",
                "page_transition" to "fade",
                "background_blur_enabled" to "false",
                "bottom_bar_style" to "floating",
                "fusion_settings_destination" to "openrose",
            ),
        )

        assertFalse(upgraded.backgroundControlEnabled)
        assertEquals(
            UserSettings(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.DARK,
                islandMode = IslandMode.MODULE,
                focusNotificationEnabled = false,
                islandTriggers = emptySet(),
                forceBle = true,
                pageTransition = PageTransition.FADE,
                backgroundBlurEnabled = false,
                bottomBarStyle = BottomBarStyle.FLOATING,
                fusionSettingsDestination = FusionSettingsDestination.OPENROSE,
            ),
            upgraded,
        )
        assertFalse(UserSettingsCodec.decode(mapOf("background_control_enabled" to "yes")).backgroundControlEnabled)
        assertFalse(UserSettingsCodec.decode(mapOf("background_control_enabled" to "")).backgroundControlEnabled)
    }

    @Test
    fun theEnabledIntentSurvivesPersistenceAndRebuildingTheRuntime() = runTest {
        val enabled = UserSettings(backgroundControlEnabled = true)
        val restored = UserSettingsCodec.decode(UserSettingsCodec.encode(enabled))
        assertEquals("true", UserSettingsCodec.encode(enabled)["background_control_enabled"])
        assertEquals("false", UserSettingsCodec.encode(UserSettings())["background_control_enabled"])

        val runtime = newRuntime(RoseTransport(), settings = restored)

        assertTrue(runtime.state.value.settings.backgroundControlEnabled)
    }

    @Test
    fun savingTheIntentIsNotReadinessUntilTheServiceReportsItIsRunning() = runTest {
        val runtime = newRuntime(RoseTransport())
        assertEquals(BackgroundControlStatus.Disabled, runtime.state.value.backgroundControl)

        runtime.updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = true))
        // Preference saved, nothing running yet: the host still has to start the service.
        assertEquals(BackgroundControlStatus.NotRunning, runtime.state.value.backgroundControl)

        runtime.updatePlatformState(androidPlatform(service = BackgroundServiceFact.Running))
        assertEquals(BackgroundControlStatus.Running(notificationVisible = true), runtime.state.value.backgroundControl)

        runtime.updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = false))
        runtime.updatePlatformState(androidPlatform(service = BackgroundServiceFact.NotRunning))
        assertEquals(BackgroundControlStatus.Disabled, runtime.state.value.backgroundControl)
    }

    @Test
    fun aMissingBluetoothPermissionIsReportedAsTheReasonNeverAsReady() = runTest {
        for (permission in listOf(PermissionState.REQUIRED, PermissionState.DENIED)) {
            val runtime = newRuntime(RoseTransport(), settings = UserSettings(backgroundControlEnabled = true))
            runtime.updatePlatformState(androidPlatform(permission = permission, service = BackgroundServiceFact.NotRunning))

            assertEquals(
                BackgroundControlStatus.Blocked(BackgroundControlBlock.BLUETOOTH_PERMISSION),
                runtime.state.value.backgroundControl,
            )
        }

        // Granting the permission later makes the pending start possible again.
        val runtime = newRuntime(RoseTransport(), settings = UserSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform(permission = PermissionState.DENIED))
        runtime.updatePlatformState(androidPlatform(permission = PermissionState.GRANTED))
        assertEquals(BackgroundControlStatus.NotRunning, runtime.state.value.backgroundControl)
    }

    @Test
    fun aRejectedServiceStartIsReportedWithItsReason() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = UserSettings(backgroundControlEnabled = true))

        runtime.updatePlatformState(
            androidPlatform(service = BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)),
        )

        assertEquals(
            BackgroundControlStatus.Blocked(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
            runtime.state.value.backgroundControl,
        )
    }

    @Test
    fun aHiddenRunningNotificationIsDisclosedWhileControlStillRuns() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = UserSettings(backgroundControlEnabled = true))

        runtime.updatePlatformState(
            androidPlatform(service = BackgroundServiceFact.Running, notifications = RunningNotification.HIDDEN),
        )

        assertEquals(BackgroundControlStatus.Running(notificationVisible = false), runtime.state.value.backgroundControl)
    }

    @Test
    fun desktopHostsHaveNoBackgroundControl() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = UserSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundControlStatus.Unsupported, runtime.state.value.backgroundControl)
    }

    @Test
    fun focusNotificationAndIslandPresentationDoNotChangeBackgroundControl() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = UserSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform(service = BackgroundServiceFact.Running))
        val running = runtime.state.value.backgroundControl

        for (islandMode in IslandMode.entries) {
            for (focus in listOf(false, true)) {
                runtime.updateSettings(
                    runtime.state.value.settings.copy(islandMode = islandMode, focusNotificationEnabled = focus),
                )
                assertEquals(running, runtime.state.value.backgroundControl)
                assertTrue(runtime.state.value.settings.backgroundControlEnabled)
            }
        }
        // And the reverse: turning background control off leaves the presentation choices alone.
        val presentation = runtime.state.value.settings.copy(backgroundControlEnabled = false)
        runtime.updateSettings(presentation)
        assertEquals(IslandMode.MODULE, runtime.state.value.settings.islandMode)
        assertTrue(runtime.state.value.settings.focusNotificationEnabled)
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
            runtime.updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = true))
            runtime.updatePlatformState(androidPlatform(service = BackgroundServiceFact.Running))
        }
        runtime.updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = false))
        runtime.updatePlatformState(androidPlatform(service = BackgroundServiceFact.NotRunning))

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
        val runtime = newRuntime(transport, settings = UserSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform(service = BackgroundServiceFact.Running))

        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        transport.audioDevice.emit(null)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.single().closed)
        assertTrue(runtime.state.value.settings.backgroundControlEnabled)
        assertEquals(BackgroundControlStatus.Running(notificationVisible = true), runtime.state.value.backgroundControl)

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
        val runtime = newRuntime(transport, settings = UserSettings(backgroundControlEnabled = true))
        assertEquals(OperationResult.Success, runtime.connect(rose.deviceId))

        runtime.updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = false))
        advanceUntilIdle()

        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    private fun rosePair() = DeviceCandidate(DeviceId("rose"), "EarFree i5", null, ModelProfile.I5_ZT, "", "11:22:33:44:55:66")

    private fun androidPlatform(
        permission: PermissionState = PermissionState.GRANTED,
        service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
        notifications: RunningNotification = RunningNotification.VISIBLE,
    ) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        backgroundService = BackgroundServiceFacts(service, notifications),
    )

    private fun TestScope.newRuntime(transport: RoseTransport, settings: UserSettings = UserSettings()): ControlRuntime =
        LocalControlRuntime(
            initialPlatformState = androidPlatform(),
            initialSettings = settings,
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
        )

    private class RoseTransport(private val bonded: List<DeviceCandidate> = emptyList()) : ControlTransport {
        val audioDevice = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
        val links = mutableListOf<RoseConnection>()

        override val audioConnectedDevice: Flow<DeviceId?> get() = audioDevice
        override suspend fun bondedDevices(): List<DeviceCandidate> = bonded
        override fun scan(): Flow<Advertisement> = emptyFlow()
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection =
            RoseConnection().also(links::add)
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

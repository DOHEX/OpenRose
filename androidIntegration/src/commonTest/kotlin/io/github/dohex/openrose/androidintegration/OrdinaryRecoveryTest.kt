package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.LocalControlRuntime
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
 * Ordinary control revived after the process disappears or the phone restarts. Everything is
 * observed through the unified control runtime; the simulated lifecycle events here are contract
 * evidence only and say nothing about a ROM actually waking the process.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrdinaryRecoveryTest {
    private var integration = IntegrationState()

    @Test
    fun nothingConnectsBeforeFirstUnlockEvenWhenTheEarphonesAreAlreadyAudioConnected() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)

        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.isEmpty())
    }

    @Test
    fun firstUnlockConnectsAnAudioConnectionThatPredatesTheOwnerUsingTheRestoredSettings() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertTrue(transport.links.isEmpty())

        // Settings are restored first, then the platform reports the unlocked storage.
        runtime.updateSettings(UserSettings(forceBle = true))
        runtime.updateIntegrationSettings(IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()

        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(1, transport.links.size)
        assertTrue(runtime.state.value.settings.forceBle)
        runtime.disconnect()
    }

    @Test
    fun anAudioConnectionThatEndedBeforeUnlockDoesNotConnectAtUnlock() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        transport.audioDevice.emit(rose.deviceId)
        transport.audioDevice.emit(null)
        runCurrent()

        runtime.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertTrue(transport.links.isEmpty())
    }

    @Test
    fun anAudioConnectionAfterUnlockConnectsAndRepeatedUnlockOrAudioEventsKeepOneConnection() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        runtime.updatePlatformState(androidPlatform(unlocked = true))
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        repeat(3) {
            runtime.updatePlatformState(androidPlatform(unlocked = true))
            transport.audioDevice.emit(rose.deviceId)
            runCurrent()
            advanceUntilIdle()
        }

        assertEquals(1, transport.links.size)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    @Test
    fun aRebuiltProcessRestoresControlFromTheSavedSettingsAndTheExistingAudioConnection() = runTest {
        val rose = rosePair()
        val saved = HostSettingsCodec.encode(
            HostSettings(UserSettings(forceBle = true), IntegrationSettings(backgroundControlEnabled = true)),
        )
        val transport = RoseTransport(bonded = listOf(rose))
        // The headset has been audio-connected the whole time; the new process only replays the current state.
        transport.audioDevice.emit(rose.deviceId)
        val rebuilt = newRuntime(transport, unlocked = false)
        runCurrent()
        assertTrue(transport.links.isEmpty())

        val restored = HostSettingsCodec.decode(saved)
        rebuilt.updateSettings(restored.control)
        rebuilt.updateIntegrationSettings(restored.integration)
        rebuilt.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()

        assertIs<ConnectionState.Connected>(rebuilt.state.value.connection)
        assertTrue(rebuilt.settings.backgroundControlEnabled)
        assertTrue(rebuilt.state.value.settings.forceBle)
        assertEquals(1, transport.links.size)
        rebuilt.disconnect()
    }

    @Test
    fun aManualDisconnectIsNotUndoneByLaterPlatformRefreshes() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, unlocked = false)
        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        runtime.updatePlatformState(androidPlatform(unlocked = true))
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        runtime.disconnect()
        repeat(3) { runtime.updatePlatformState(androidPlatform(unlocked = true)) }
        runCurrent()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertEquals(1, transport.links.size)
    }

    @Test
    fun disablingBackgroundControlTakesPriorityOverEveryPrerequisite() = runTest {
        val runtime = newRuntime(RoseTransport())
        runtime.report(androidPlatform(), armedWake(WakeBand.ANDROID_12_13))
        assertEquals(BackgroundRecovery.Disabled, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))

        runtime.updateIntegrationSettings(IntegrationSettings(backgroundControlEnabled = true))
        integration = integration.copy(settings = runtime.settings)
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_12_13), integration.backgroundRecovery(runtime.state.value))
        assertTrue(integration.mayStartBackgroundService(runtime.state.value))

        runtime.updateIntegrationSettings(IntegrationSettings(backgroundControlEnabled = false))
        integration = integration.copy(settings = runtime.settings)
        assertEquals(BackgroundRecovery.Disabled, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))
    }

    @Test
    fun noServiceMayStartBeforeUnlockOrWithoutBluetoothPermission() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true), unlocked = false)
        runtime.report(androidPlatform(unlocked = false), armedWake(WakeBand.ANDROID_8_11))
        assertEquals(BackgroundRecovery.WaitingForUnlock, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))

        runtime.report(androidPlatform(permission = PermissionState.DENIED), armedWake(WakeBand.ANDROID_8_11))
        assertEquals(
            BackgroundRecovery.Incomplete(listOf(WakePrerequisite.BLUETOOTH_PERMISSION)),
            integration.backgroundRecovery(runtime.state.value),
        )
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))
    }

    @Test
    fun oldAndroidNeedsNoAssociationButNewerAndroidIsNeverReadyWithoutAnObservedAssociation() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))

        runtime.report(androidPlatform(), WakeFacts(WakeBand.ANDROID_8_11, CompanionAssociation.NOT_REQUIRED, PresenceObservation.NOT_REQUIRED))
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_8_11), integration.backgroundRecovery(runtime.state.value))

        for (band in listOf(WakeBand.ANDROID_12_13, WakeBand.ANDROID_14_PLUS)) {
            runtime.report(androidPlatform(), WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))
            assertEquals(
                BackgroundRecovery.Incomplete(listOf(WakePrerequisite.COMPANION_ASSOCIATION, WakePrerequisite.PRESENCE_OBSERVATION)),
                integration.backgroundRecovery(runtime.state.value),
            )

            runtime.report(androidPlatform(), WakeFacts(band, CompanionAssociation.ACTIVE, PresenceObservation.NOT_OBSERVING))
            assertEquals(
                BackgroundRecovery.Incomplete(listOf(WakePrerequisite.PRESENCE_OBSERVATION)),
                integration.backgroundRecovery(runtime.state.value),
            )

            runtime.report(androidPlatform(), WakeFacts(band, CompanionAssociation.ACTIVE, PresenceObservation.REFUSED))
            assertEquals(
                BackgroundRecovery.Incomplete(listOf(WakePrerequisite.PRESENCE_OBSERVATION)),
                integration.backgroundRecovery(runtime.state.value),
            )

            runtime.report(androidPlatform(), WakeFacts(band, CompanionAssociation.ACTIVE, PresenceObservation.OBSERVING))
            assertEquals(BackgroundRecovery.Armed(band), integration.backgroundRecovery(runtime.state.value))

            // The user cancels the association in system settings: readiness disappears again.
            runtime.report(androidPlatform(), WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))
            assertIs<BackgroundRecovery.Incomplete>(integration.backgroundRecovery(runtime.state.value))
        }
    }

    @Test
    fun anIncompleteWakeStillLetsTheServiceStartFromAUserEntryAndForegroundControlKeepsWorking() = runTest {
        val rose = rosePair()
        val transport = RoseTransport(bonded = listOf(rose))
        val runtime = newRuntime(transport, settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.report(androidPlatform(), WakeFacts(WakeBand.ANDROID_14_PLUS, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))

        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()

        // Not ready for unattended recovery, yet the ordinary foreground path is unaffected.
        assertIs<BackgroundRecovery.Incomplete>(integration.backgroundRecovery(runtime.state.value))
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertTrue(integration.mayStartBackgroundService(runtime.state.value))
        runtime.disconnect()
    }

    @Test
    fun desktopHostsHaveNoRecovery() = runTest {
        val runtime = newRuntime(RoseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.report(androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundRecovery.Unsupported, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))
    }

    private fun rosePair() = DeviceCandidate(DeviceId("rose"), "EarFree i5", null, ModelProfile.I5_ZT, "", "11:22:33:44:55:66")

    private fun androidPlatform(
        permission: PermissionState = PermissionState.GRANTED,
        unlocked: Boolean = true,
    ) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        userUnlocked = unlocked,
    )

    /** The platform reports its control facts to the runtime and its Android wake facts to the integration state. */
    private fun SystemEntryRuntime.report(platform: PlatformState, wake: WakeFacts = WakeFacts()) {
        integration = IntegrationState(settings = settings, platform = IntegrationPlatformState(backgroundService = BackgroundServiceFacts(wake = wake)))
        updatePlatformState(platform)
    }

    private fun armedWake(band: WakeBand): WakeFacts {
        val needsAssociation = band != WakeBand.ANDROID_8_11
        return WakeFacts(
            band,
            if (needsAssociation) CompanionAssociation.ACTIVE else CompanionAssociation.NOT_REQUIRED,
            if (needsAssociation) PresenceObservation.OBSERVING else PresenceObservation.NOT_REQUIRED,
        )
    }

    private fun TestScope.newRuntime(
        transport: RoseTransport,
        settings: IntegrationSettings = IntegrationSettings(),
        unlocked: Boolean = true,
    ): SystemEntryRuntime {
        integration = IntegrationState(settings = settings)
        return SystemEntryRuntime(
            control = LocalControlRuntime(
                initialPlatformState = androidPlatform(unlocked = unlocked),
                transport = transport,
                scope = backgroundScope,
                timing = SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
            ),
            scope = backgroundScope,
            initialIntegrationSettings = settings,
        )
    }

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

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppRecoveryTest {
    private var integration = IntegrationState()

    @Test
    fun disablingBackgroundControlTakesPriorityOverEveryPrerequisite() = runTest {
        val runtime = newRuntime(roseTransport())
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
        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true), unlocked = false)
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
        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))

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

            runtime.report(androidPlatform(), WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))
            assertIs<BackgroundRecovery.Incomplete>(integration.backgroundRecovery(runtime.state.value))
        }
    }

    @Test
    fun anIncompleteWakeStillLetsTheServiceStartFromAUserEntryAndForegroundControlKeepsWorking() = runTest {
        val rose = rosePair("rose")
        val transport = roseTransport(listOf(rose))
        val runtime = newRuntime(transport, settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.report(androidPlatform(), WakeFacts(WakeBand.ANDROID_14_PLUS, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))

        transport.audioDevice.emit(rose.deviceId)
        runCurrent()
        advanceUntilIdle()

        assertIs<BackgroundRecovery.Incomplete>(integration.backgroundRecovery(runtime.state.value))
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertTrue(integration.mayStartBackgroundService(runtime.state.value))
        runtime.disconnect()
    }

    @Test
    fun desktopHostsHaveNoRecovery() = runTest {
        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.report(androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundRecovery.Unsupported, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))
    }

    private fun roseTransport(bonded: List<DeviceCandidate> = emptyList()) = HeadsetTransport("app", SharedHeadset(), bonded = bonded)

    private fun androidPlatform(
        permission: PermissionState = PermissionState.GRANTED,
        unlocked: Boolean = true,
    ) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        userUnlocked = unlocked,
    )

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
        transport: HeadsetTransport,
        settings: IntegrationSettings = IntegrationSettings(),
        unlocked: Boolean = true,
    ): SystemEntryRuntime {
        integration = IntegrationState(settings = settings)
        return SystemEntryRuntime(
            control = LocalControlRuntime(
                initialPlatformState = androidPlatform(unlocked = unlocked),
                transport = transport,
                scope = backgroundScope,
                timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
            ),
            scope = backgroundScope,
            initialIntegrationSettings = settings,
        )
    }
}

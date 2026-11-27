package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppRecoveryTest {
    private var integration = IntegrationState()

    @Test
    fun disablingBackgroundControlTakesPriorityOverEveryPrerequisite() = runTest {
        val runtime = newRuntime(appHeadset())
        report(runtime, androidPlatform(), armedWake(WakeBand.ANDROID_12_13))
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
        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true), unlocked = false)
        report(runtime, androidPlatform(unlocked = false), armedWake(WakeBand.ANDROID_8_11))
        assertEquals(BackgroundRecovery.WaitingForUnlock, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))

        report(runtime, androidPlatform(permission = PermissionState.DENIED), armedWake(WakeBand.ANDROID_8_11))
        assertEquals(
            BackgroundRecovery.Incomplete(listOf(WakePrerequisite.BLUETOOTH_PERMISSION)),
            integration.backgroundRecovery(runtime.state.value),
        )
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))
    }

    @Test
    fun oldAndroidNeedsNoAssociationButNewerAndroidIsNeverReadyWithoutAnObservedAssociation() = runTest {
        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))

        report(runtime, androidPlatform(), WakeFacts(WakeBand.ANDROID_8_11, CompanionAssociation.NOT_REQUIRED, PresenceObservation.NOT_REQUIRED))
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_8_11), integration.backgroundRecovery(runtime.state.value))

        for (band in listOf(WakeBand.ANDROID_12_13, WakeBand.ANDROID_14_PLUS)) {
            report(runtime, androidPlatform(), WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))
            assertEquals(
                BackgroundRecovery.Incomplete(listOf(WakePrerequisite.COMPANION_ASSOCIATION, WakePrerequisite.PRESENCE_OBSERVATION)),
                integration.backgroundRecovery(runtime.state.value),
            )

            report(runtime, androidPlatform(), WakeFacts(band, CompanionAssociation.ACTIVE, PresenceObservation.NOT_OBSERVING))
            assertEquals(
                BackgroundRecovery.Incomplete(listOf(WakePrerequisite.PRESENCE_OBSERVATION)),
                integration.backgroundRecovery(runtime.state.value),
            )

            report(runtime, androidPlatform(), WakeFacts(band, CompanionAssociation.ACTIVE, PresenceObservation.REFUSED))
            assertEquals(
                BackgroundRecovery.Incomplete(listOf(WakePrerequisite.PRESENCE_OBSERVATION)),
                integration.backgroundRecovery(runtime.state.value),
            )

            report(runtime, androidPlatform(), WakeFacts(band, CompanionAssociation.ACTIVE, PresenceObservation.OBSERVING))
            assertEquals(BackgroundRecovery.Armed(band), integration.backgroundRecovery(runtime.state.value))

            report(runtime, androidPlatform(), WakeFacts(band, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))
            assertIs<BackgroundRecovery.Incomplete>(integration.backgroundRecovery(runtime.state.value))
        }
    }

    @Test
    fun anIncompleteWakeStillLetsTheServiceStartFromAUserEntryAndForegroundControlKeepsWorking() = runTest {
        val app = appHeadset()
        app.audioConnected = true
        val runtime = newRuntime(app, settings = IntegrationSettings(backgroundControlEnabled = true))
        report(runtime, androidPlatform(), WakeFacts(WakeBand.ANDROID_14_PLUS, CompanionAssociation.MISSING, PresenceObservation.NOT_OBSERVING))

        eventually("a connected control session") { runtime.state.value.connection is ConnectionState.Connected }

        assertIs<BackgroundRecovery.Incomplete>(integration.backgroundRecovery(runtime.state.value))
        assertTrue(integration.mayStartBackgroundService(runtime.state.value))
        runtime.disconnect()
    }

    @Test
    fun desktopHostsHaveNoRecovery() = runTest {
        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))
        report(runtime, androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundRecovery.Unsupported, integration.backgroundRecovery(runtime.state.value))
        assertFalse(integration.mayStartBackgroundService(runtime.state.value))
    }

    private fun androidPlatform(
        permission: PermissionState = PermissionState.GRANTED,
        unlocked: Boolean = true,
    ) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        userUnlocked = unlocked,
        scanPermission = PermissionState.GRANTED,
    )

    private fun TestScope.report(runtime: SystemEntryRuntime, platform: PlatformState, wake: WakeFacts = WakeFacts()) {
        integration = IntegrationState(settings = runtime.settings, platform = IntegrationPlatformState(backgroundService = BackgroundServiceFacts(wake = wake)))
        runtime.updatePlatformState(platform)
        eventually("the core to report the platform") {
            val reported = runtime.state.value.platform
            reported.permission == platform.permission && reported.hostPlatform == platform.hostPlatform && reported.userUnlocked == platform.userUnlocked
        }
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
        app: AppHeadset,
        settings: IntegrationSettings = IntegrationSettings(),
        unlocked: Boolean = true,
    ): SystemEntryRuntime {
        integration = IntegrationState(settings = settings)
        return SystemEntryRuntime(
            control = app.runtime(backgroundScope, UserSettings(), androidPlatform(unlocked = unlocked), sessionIdBase = 0L),
            scope = backgroundScope,
            initialIntegrationSettings = settings,
        )
    }
}

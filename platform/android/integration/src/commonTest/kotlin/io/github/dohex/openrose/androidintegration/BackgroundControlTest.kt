package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackgroundControlTest {
    @Test
    fun savingTheIntentIsNotReadinessUntilTheServiceReportsItIsRunning() = runTest {
        val runtime = newRuntime(appHeadset())
        assertEquals(BackgroundControlStatus.Disabled, status(runtime))

        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = true))
        assertEquals(BackgroundControlStatus.NotRunning, status(runtime))

        report(runtime, androidPlatform())
        assertEquals(BackgroundControlStatus.Running(notificationVisible = true), status(runtime, BackgroundServiceFact.Running))

        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
        report(runtime, androidPlatform())
        assertEquals(BackgroundControlStatus.Disabled, status(runtime, BackgroundServiceFact.NotRunning))
    }

    @Test
    fun aMissingBluetoothPermissionIsReportedAsTheReasonNeverAsReady() = runTest {
        for (permission in listOf(PermissionState.REQUIRED, PermissionState.DENIED)) {
            val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))
            report(runtime, androidPlatform(permission = permission))

            assertEquals(
                BackgroundControlStatus.Blocked(BackgroundControlBlock.BLUETOOTH_PERMISSION),
                status(runtime, BackgroundServiceFact.NotRunning),
            )
        }

        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))
        report(runtime, androidPlatform(permission = PermissionState.DENIED))
        report(runtime, androidPlatform(permission = PermissionState.GRANTED))
        assertEquals(BackgroundControlStatus.NotRunning, status(runtime))
    }

    @Test
    fun aRejectedServiceStartIsReportedWithItsReason() = runTest {
        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))

        report(runtime, androidPlatform())

        assertEquals(
            BackgroundControlStatus.Blocked(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
            status(runtime, BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)),
        )
    }

    @Test
    fun aHiddenRunningNotificationIsDisclosedWhileControlStillRuns() = runTest {
        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))

        report(runtime, androidPlatform())

        assertEquals(
            BackgroundControlStatus.Running(notificationVisible = false),
            status(runtime, BackgroundServiceFact.Running, RunningNotification.HIDDEN),
        )
    }

    @Test
    fun desktopHostsHaveNoBackgroundControl() = runTest {
        val runtime = newRuntime(appHeadset(), settings = IntegrationSettings(backgroundControlEnabled = true))
        report(runtime, androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundControlStatus.Unsupported, status(runtime))
    }

    @Test
    fun enablingDisablingAndRepeatingTheIntentNeverCreatesOrEndsAControlConnection() = runTest {
        val headset = SharedHeadset()
        val earphones = rosePair(APP_HEADSET_ADDRESS)
        val app = appHeadset(headset, earphones)
        val runtime = newRuntime(app, settings = IntegrationSettings(backgroundControlEnabled = true))
        assertEquals(OperationResult.Success, runtime.connect(earphones.deviceId))
        eventually("a connected control session") { runtime.state.value.connection is ConnectionState.Connected }
        val before = assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        repeat(3) {
            runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
            runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = true))
            report(runtime, androidPlatform())
        }
        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
        report(runtime, androidPlatform())
        quiesce()

        assertEquals(before, runtime.state.value.connection)
        assertEquals(1, app.connections)
        assertEquals(listOf("app:open"), headset.events)
        runtime.disconnect()
    }

    private fun androidPlatform(permission: PermissionState = PermissionState.GRANTED) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        scanPermission = PermissionState.GRANTED,
    )

    private fun TestScope.report(runtime: SystemEntryRuntime, platform: PlatformState) {
        runtime.updatePlatformState(platform)
        eventually("the core to report the platform") {
            val reported = runtime.state.value.platform
            reported.permission == platform.permission && reported.hostPlatform == platform.hostPlatform
        }
    }

    private fun status(
        runtime: SystemEntryRuntime,
        service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
        notifications: RunningNotification = RunningNotification.VISIBLE,
    ): BackgroundControlStatus = IntegrationState(
        settings = runtime.settings,
        platform = IntegrationPlatformState(backgroundService = BackgroundServiceFacts(service, notifications)),
    ).backgroundControl(runtime.state.value)

    private fun TestScope.newRuntime(app: AppHeadset, settings: IntegrationSettings = IntegrationSettings()): SystemEntryRuntime = SystemEntryRuntime(
        control = app.runtime(backgroundScope, UserSettings(), androidPlatform(), sessionIdBase = 0L),
        scope = backgroundScope,
        initialIntegrationSettings = settings,
    )
}

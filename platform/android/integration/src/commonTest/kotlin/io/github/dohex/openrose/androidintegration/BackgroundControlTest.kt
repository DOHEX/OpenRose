package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackgroundControlTest {
    @Test
    fun savingTheIntentIsNotReadinessUntilTheServiceReportsItIsRunning() = runTest {
        val runtime = newRuntime(roseTransport())
        assertEquals(BackgroundControlStatus.Disabled, status(runtime))

        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = true))
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
            val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
            runtime.updatePlatformState(androidPlatform(permission = permission))

            assertEquals(
                BackgroundControlStatus.Blocked(BackgroundControlBlock.BLUETOOTH_PERMISSION),
                status(runtime, BackgroundServiceFact.NotRunning),
            )
        }

        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform(permission = PermissionState.DENIED))
        runtime.updatePlatformState(androidPlatform(permission = PermissionState.GRANTED))
        assertEquals(BackgroundControlStatus.NotRunning, status(runtime))
    }

    @Test
    fun aRejectedServiceStartIsReportedWithItsReason() = runTest {
        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))

        runtime.updatePlatformState(androidPlatform())

        assertEquals(
            BackgroundControlStatus.Blocked(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
            status(runtime, BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED)),
        )
    }

    @Test
    fun aHiddenRunningNotificationIsDisclosedWhileControlStillRuns() = runTest {
        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))

        runtime.updatePlatformState(androidPlatform())

        assertEquals(
            BackgroundControlStatus.Running(notificationVisible = false),
            status(runtime, BackgroundServiceFact.Running, RunningNotification.HIDDEN),
        )
    }

    @Test
    fun desktopHostsHaveNoBackgroundControl() = runTest {
        val runtime = newRuntime(roseTransport(), settings = IntegrationSettings(backgroundControlEnabled = true))
        runtime.updatePlatformState(androidPlatform().copy(hostPlatform = HostPlatform.LINUX))

        assertEquals(BackgroundControlStatus.Unsupported, status(runtime))
    }

    @Test
    fun enablingDisablingAndRepeatingTheIntentNeverCreatesOrEndsAControlConnection() = runTest {
        val rose = rosePair("rose")
        val transport = roseTransport(listOf(rose))
        val runtime = newRuntime(transport, settings = IntegrationSettings(backgroundControlEnabled = true))
        assertEquals(OperationResult.Success, runtime.connect(rose.deviceId))
        val before = assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        repeat(3) {
            runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
            runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = true))
            runtime.updatePlatformState(androidPlatform())
        }
        runtime.updateIntegrationSettings(runtime.settings.copy(backgroundControlEnabled = false))
        runtime.updatePlatformState(androidPlatform())
        advanceUntilIdle()

        assertEquals(before, runtime.state.value.connection)
        assertEquals(1, transport.links.size)
        assertFalse(transport.links.single().closed)
        runtime.disconnect()
    }

    private fun roseTransport(bonded: List<DeviceCandidate> = emptyList()) = HeadsetTransport("app", SharedHeadset(), bonded = bonded)

    private fun androidPlatform(permission: PermissionState = PermissionState.GRANTED) = PlatformState(
        permission = permission,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
    )

    private fun status(
        runtime: SystemEntryRuntime,
        service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
        notifications: RunningNotification = RunningNotification.VISIBLE,
    ): BackgroundControlStatus = IntegrationState(
        settings = runtime.settings,
        platform = IntegrationPlatformState(backgroundService = BackgroundServiceFacts(service, notifications)),
    ).backgroundControl(runtime.state.value)

    private fun TestScope.newRuntime(transport: HeadsetTransport, settings: IntegrationSettings = IntegrationSettings()): SystemEntryRuntime = SystemEntryRuntime(
        control = LocalControlRuntime(
            initialPlatformState = androidPlatform(),
            transport = transport,
            scope = backgroundScope,
            timing = SessionTiming(queryTimeoutMillis = 50, writeIntervalMillis = 0),
        ),
        scope = backgroundScope,
        initialIntegrationSettings = settings,
    )
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The system-module facts a platform reports reach the integration state, apart from the control state. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IntegrationPlatformTest {
    @Test
    fun anUnimplementedPlatformStartsDisconnectedAndReportsItsModuleLimitation() = runTest {
        val runtime = OwnerRig(this).newRuntime(
            interfaceOpen = false,
            integrationPlatform = IntegrationPlatformState(
                module = ModuleAvailability.Unavailable("System integration is Android only"),
            ),
        )

        assertIs<ConnectionState.Disconnected>(runtime.state.value.connection)
        assertEquals(
            ModuleAvailability.Unavailable("System integration is Android only"),
            runtime.integration.value.platform.module,
        )
    }

    @Test
    fun moduleEvidenceIsObservableWithoutClaimingADeviceConnection() = runTest {
        val runtime = OwnerRig(this).newRuntime(interfaceOpen = false)
        val states = mutableListOf<IntegrationState>()
        val observation = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.integration.take(2).toList(states)
        }

        runtime.updateIntegrationPlatform(IntegrationPlatformState(module = ModuleAvailability.NotEnabled))
        runCurrent()
        observation.join()

        assertEquals(ModuleAvailability.NotEnabled, states.last().platform.module)
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
    }

    @Test
    fun modulePresentationFollowsTheConnectionPromptExpiryAndDisconnect() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE))
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()

        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()

        val device = assertNotNull(runtime.state.value.device)
        val reference = SessionReference(device.deviceId, device.sessionId)
        val presentation = runtime.integration.value.presentation
        assertEquals(reference, presentation.reference)
        val battery = assertNotNull(presentation.battery)
        assertEquals(PresentationReading.Known(100), battery.left)
        assertEquals(PresentationReading.Known(98), battery.right)
        assertEquals(PresentationReading.NotProvided, battery.case)
        assertEquals(PresentationReading.Known("wind"), assertNotNull(presentation.noise).mode)
        assertTrue(DeviceSetting.NOISE_MODE in presentation.actions)
        assertTrue(presentation.showStatusNotification)
        val prompt = assertNotNull(presentation.connectionPrompt)
        assertEquals(reference, prompt.reference)
        val snapshot = assertIs<SessionSnapshotResult.Available>(runtime.readSession(reference))
        assertEquals(presentation, snapshot.presentation)

        advanceTimeBy(prompt.durationMillis)
        runCurrent()
        assertNull(runtime.integration.value.presentation.connectionPrompt)
        assertEquals(reference, runtime.integration.value.presentation.reference)
        assertTrue(runtime.integration.value.presentation.showStatusNotification)

        assertTrue(runtime.disconnect())
        runCurrent()
        assertNull(runtime.integration.value.presentation.reference)
        assertNull(runtime.integration.value.presentation.connectionPrompt)
    }

    @Test
    fun moduleSessionSnapshotsNeverMixPresentationAcrossReconnects() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE))
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        val snapshots = mutableListOf<SessionSnapshotResult.Available>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.state.collect { state ->
                state.readySession?.let { reference ->
                    val snapshot = runtime.readSession(reference)
                    if (snapshot is SessionSnapshotResult.Available) snapshots += snapshot
                }
            }
        }

        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        val first = assertNotNull(runtime.state.value.readySession)
        assertTrue(runtime.disconnect())
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        val second = assertNotNull(runtime.state.value.readySession)

        assertEquals(setOf(first, second), snapshots.map { SessionReference(it.device.deviceId, it.device.sessionId) }.toSet())
        snapshots.forEach { snapshot ->
            assertEquals(SessionReference(snapshot.device.deviceId, snapshot.device.sessionId), snapshot.presentation.reference)
        }
        assertTrue(runtime.disconnect())
    }
}

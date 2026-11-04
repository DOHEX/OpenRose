package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlWay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A host process that is alive but no longer answers is not "connected": the application must stop
 * showing its last reading as current, and must take the readings afresh once the host answers again.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModuleHostProbeTest {
    private val saved = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null))

    private suspend fun kotlinx.coroutines.test.TestScope.connected(): Pair<OwnerRig, SwitchingControlRuntime> {
        val rig = OwnerRig(this, initial = saved)
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNotNull(runtime.state.value.device)
        return rig to runtime
    }

    @Test
    fun aHostThatStopsAnsweringIsReportedUnresponsiveAndItsReadingIsWithdrawn() = runTest {
        val (rig, runtime) = connected()
        rig.link.statusHangs = true

        // Two unanswered probes are not yet enough: one slow answer must not flip a healthy host.
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)

        advanceTimeBy(2_000)
        runCurrent()
        val state = runtime.state.value
        val integration = runtime.integration.value
        val readiness = assertIs<OwnerReadiness.Unavailable>(integration.owner.readiness)
        assertEquals(OwnerFailureStage.CONNECTION, readiness.stage)
        assertEquals(UNRESPONSIVE_REASON, readiness.reason)
        assertEquals(ModuleAvailability.Unresponsive, integration.platform.module)
        assertNull(state.device, "the last reading must not stay on screen as current")
    }

    @Test
    fun aHostThatAnswersAgainIsReadAfreshAndReportedAvailable() = runTest {
        val (rig, runtime) = connected()
        rig.link.statusHangs = true
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ModuleAvailability.Unresponsive, runtime.integration.value.platform.module)

        rig.link.statusHangs = false
        advanceTimeBy(10_000)
        runCurrent()

        val state = runtime.state.value
        val integration = runtime.integration.value
        assertEquals(OwnerReadiness.Ready, integration.owner.readiness)
        assertEquals(ModuleAvailability.Available, integration.platform.module)
        assertNotNull(state.device)
    }

    @Test
    fun aQuietHostThatStillAnswersStaysAvailable() = runTest {
        val (_, runtime) = connected()

        // Frames follow device changes; minutes without one say nothing about the host.
        advanceTimeBy(5 * 60_000)
        runCurrent()

        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(ModuleAvailability.Available, runtime.integration.value.platform.module)
    }
}

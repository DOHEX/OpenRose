package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.OperationResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppSessionHoldTest {
    @Test
    fun closingTheInterfaceWithBackgroundControlOffReleasesTheControlConnectionAndNothingReconnects() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        runCurrent()
        assertNull(runtime.integration.value.owner.holder)
        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)

        val ui = runtime.holdAppSession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)

        ui.release()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("app:open", "app:close"), rig.headset.events)
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertNull(runtime.integration.value.owner.holder)
        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)
        assertEquals(ControlWay.APP, runtime.integration.value.owner.selected)

        rig.appTransport.audioDevice.emit(null)
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, rig.headset.opens("app"))
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
    }

    @Test
    fun reopeningAfterARefusedLastCloseRecoversOnlyAfterTheOldHandleIsReleased() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val first = runtime.holdAppSession()
        val device = rig.earphones.deviceId
        assertEquals(OperationResult.Success, runtime.connect(device))
        rig.appTransport.closeFails = true
        first.release()
        runCurrent()
        rig.appTransport.audioDevice.emit(device)
        runCurrent()
        assertEquals(1, rig.headset.opens("app"))

        val reopened = runtime.holdAppSession()
        val waiting = assertIs<OwnerReadiness.Unavailable>(runtime.integration.value.owner.readiness)
        assertEquals(OwnerFailureStage.RELEASE, waiting.stage)
        assertIs<OperationResult.Failure>(runtime.connect(device))
        assertEquals(1, rig.headset.opens("app"))

        rig.appTransport.closeFails = false
        advanceTimeBy(rig.clientTiming.retryMillis)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(OperationResult.Success, runtime.connect(device))
        runCurrent()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(listOf("app:open", "app:close", "app:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        reopened.release()
    }

    @Test
    fun withBackgroundControlOnTheServiceKeepsOneConnectionAcrossInterfaceCloseAndReopen() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        runtime.updateIntegrationSettings(runtime.integration.value.settings.copy(backgroundControlEnabled = true))
        val service = runtime.holdAppSession()
        var ui = runtime.holdAppSession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        val session = assertNotNull(runtime.state.value.device).sessionId
        val generation = assertNotNull(runtime.integration.value.owner.holder).generation

        ui.release()
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(listOf("app:open"), rig.headset.events)

        ui = runtime.holdAppSession()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("app:open"), rig.headset.events)
        assertEquals(session, assertNotNull(runtime.state.value.device).sessionId)
        assertEquals(generation, assertNotNull(runtime.integration.value.owner.holder).generation)

        service.release()
        ui.release()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("app:open", "app:close"), rig.headset.events)
    }

    @Test
    fun repeatedHoldsShareOneSessionAndOnlyTheLastReleaseEndsIt() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val ui = runtime.holdAppSession()
        val service = runtime.holdAppSession()
        val wake = runtime.holdAppSession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        val generation = assertNotNull(runtime.integration.value.owner.holder).generation

        val again = runtime.holdAppSession()
        assertEquals(generation, assertNotNull(runtime.integration.value.owner.holder).generation)
        ui.release()
        ui.release()
        runCurrent()
        service.release()
        wake.release()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("app:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        again.release()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("app:open", "app:close"), rig.headset.events)
    }

    @Test
    fun releasingAndHoldingAgainBeforeTheSessionEndedDoesNotLoseTheSession() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val first = runtime.holdAppSession()
        runCurrent()

        first.release()
        val second = runtime.holdAppSession()
        runCurrent()
        advanceUntilIdle()

        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNotNull(runtime.integration.value.owner.holder)
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("app:open"), rig.headset.events)
        second.release()
    }

    @Test
    fun moduleControlNeverCreatesTheAppSessionWhateverHoldsExist() = runTest {
        val module = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        module.bootHost()
        val runtime = module.newRuntime(interfaceOpen = false)
        runtime.holdAppSession()
        runtime.holdAppSession()
        module.appTransport.audioDevice.emit(module.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertTrue(module.appTransport.links.isEmpty())
        assertEquals(0, module.headset.opens("app"))
        assertTrue(runtime.integration.value.owner.holder?.way != ControlWay.APP)

        val rig = OwnerRig(this)
        rig.bootHost()
        val held = rig.newRuntime()
        runCurrent()
        assertIs<HandoffOutcome.Completed>(held.selectControlWay(ControlWay.MODULE))
        runCurrent()
        held.holdAppSession()
        runCurrent()
        advanceUntilIdle()
        assertEquals(ControlWay.MODULE, held.integration.value.owner.selected)
        assertTrue(held.integration.value.owner.holder?.way != ControlWay.APP)
        assertTrue(rig.appTransport.links.isEmpty())

        assertIs<HandoffOutcome.Completed>(held.selectControlWay(ControlWay.APP))
        runCurrent()
        assertEquals(ControlWay.APP, held.integration.value.owner.holder?.way)
    }

    @Test
    fun nothingHoldsTheSessionBeforeFirstUnlockAndTheHoldsTakeEffectAtUnlock() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(userUnlocked = false, interfaceOpen = false)
        val hold = runtime.holdAppSession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertTrue(rig.appTransport.links.isEmpty())

        runtime.onUserUnlocked(HostSettings())
        runCurrent()
        advanceUntilIdle()

        assertEquals(ControlWay.APP, runtime.integration.value.owner.holder?.way)
        assertEquals(listOf("app:open"), rig.headset.events)
        hold.release()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("app:open", "app:close"), rig.headset.events)
    }
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.OperationResult
import kotlinx.coroutines.test.advanceTimeBy
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
    private suspend fun OwnerRig.connected(runtime: SwitchingControlRuntime) {
        assertEquals(OperationResult.Success, runtime.connect(earphones.deviceId))
        eventually("a connected control session") { runtime.state.value.connection is ConnectionState.Connected }
    }

    private fun OwnerRig.waitConnected(runtime: SwitchingControlRuntime) {
        eventually("a connected control session") { runtime.state.value.connection is ConnectionState.Connected }
    }

    @Test
    fun closingTheInterfaceWithBackgroundControlOffReleasesTheControlConnectionAndNothingReconnects() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        runCurrent()
        assertNull(runtime.integration.value.owner.holder)
        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)

        rig.app.audioConnected = true
        val ui = runtime.holdAppSession()
        rig.waitConnected(runtime)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)

        ui.release()
        rig.eventually("the control connection to close") { rig.headset.events == listOf("app:open", "app:close") }
        rig.eventually("the app owner to retire") { runtime.integration.value.owner.holder == null }

        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)
        assertEquals(ControlWay.APP, runtime.integration.value.owner.selected)

        rig.app.audioConnected = false
        rig.app.audioConnected = true
        rig.quiesce()
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
        rig.connected(runtime)
        rig.app.closeFails = true
        first.release()
        rig.eventually("the core to report the close refused") { rig.app.refusedReleases >= 1 }
        assertEquals(1, rig.headset.opens("app"))

        val reopened = runtime.holdAppSession()
        val waiting = assertIs<OwnerReadiness.Unavailable>(runtime.integration.value.owner.readiness)
        assertEquals(OwnerFailureStage.RELEASE, waiting.stage)
        assertIs<OperationResult.Failure>(runtime.connect(device))
        assertEquals(1, rig.headset.opens("app"))
        assertEquals(0, rig.app.closedRuntimes)

        rig.app.closeFails = false
        advanceTimeBy(rig.clientTiming.retryMillis)
        rig.eventually("the retried close to hand the session back") { runtime.integration.value.owner.readiness == OwnerReadiness.Ready }
        assertEquals(1, rig.app.closedRuntimes)
        rig.connected(runtime)
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
        rig.app.audioConnected = true
        val service = runtime.holdAppSession()
        var ui = runtime.holdAppSession()
        rig.waitConnected(runtime)
        rig.eventually("a ready session") { runtime.state.value.device != null }
        val session = assertNotNull(runtime.state.value.device).sessionId
        val generation = assertNotNull(runtime.integration.value.owner.holder).generation

        ui.release()
        rig.quiesce()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(listOf("app:open"), rig.headset.events)

        ui = runtime.holdAppSession()
        rig.quiesce()

        assertEquals(listOf("app:open"), rig.headset.events)
        assertEquals(session, assertNotNull(runtime.state.value.device).sessionId)
        assertEquals(generation, assertNotNull(runtime.integration.value.owner.holder).generation)

        service.release()
        ui.release()
        rig.eventually("the control connection to close") { rig.headset.events == listOf("app:open", "app:close") }
    }

    @Test
    fun repeatedHoldsShareOneSessionAndOnlyTheLastReleaseEndsIt() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        rig.app.audioConnected = true
        val ui = runtime.holdAppSession()
        val service = runtime.holdAppSession()
        val wake = runtime.holdAppSession()
        rig.waitConnected(runtime)
        val generation = assertNotNull(runtime.integration.value.owner.holder).generation

        val again = runtime.holdAppSession()
        assertEquals(generation, assertNotNull(runtime.integration.value.owner.holder).generation)
        ui.release()
        ui.release()
        runCurrent()
        service.release()
        wake.release()
        rig.quiesce()

        assertEquals(listOf("app:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        again.release()
        rig.eventually("the control connection to close") { rig.headset.events == listOf("app:open", "app:close") }
    }

    @Test
    fun releasingAndHoldingAgainBeforeTheSessionEndedDoesNotLoseTheSession() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        rig.app.audioConnected = true
        val first = runtime.holdAppSession()
        runCurrent()

        first.release()
        val second = runtime.holdAppSession()
        rig.quiesce()

        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNotNull(runtime.integration.value.owner.holder)
        rig.waitConnected(runtime)
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
        module.app.audioConnected = true
        module.quiesce()
        assertEquals(0, module.app.connections)
        assertEquals(0, module.headset.opens("app"))
        assertTrue(runtime.integration.value.owner.holder?.way != ControlWay.APP)

        val rig = OwnerRig(this)
        rig.bootHost()
        val held = rig.newRuntime()
        runCurrent()
        assertIs<HandoffOutcome.Completed>(held.selectControlWay(ControlWay.MODULE))
        runCurrent()
        held.holdAppSession()
        rig.quiesce()
        assertEquals(ControlWay.MODULE, held.integration.value.owner.selected)
        assertTrue(held.integration.value.owner.holder?.way != ControlWay.APP)
        assertEquals(0, rig.app.connections)

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
        rig.app.audioConnected = true
        rig.quiesce()
        assertEquals(0, rig.app.connections)

        runtime.onUserUnlocked(HostSettings())
        runCurrent()

        assertEquals(ControlWay.APP, runtime.integration.value.owner.holder?.way)
        rig.eventually("a connected control session") { runtime.state.value.connection is ConnectionState.Connected }
        assertEquals(listOf("app:open"), rig.headset.events)
        hold.release()
        rig.eventually("the control connection to close") { rig.headset.events == listOf("app:open", "app:close") }
    }
}

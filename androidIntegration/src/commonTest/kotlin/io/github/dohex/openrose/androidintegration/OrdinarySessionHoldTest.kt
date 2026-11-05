package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The ordinary session lives exactly as long as someone needs it: the visible interface and the
 * background-control service each hold it. Observed only through the unified runtime, over a real
 * ordinary session and a transport that stands for the earphones.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrdinarySessionHoldTest {
    @Test
    fun closingTheInterfaceWithBackgroundControlOffReleasesTheControlConnectionAndNothingReconnects() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        runCurrent()
        assertNull(runtime.integration.value.owner.holder)
        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)

        val ui = runtime.holdOrdinarySession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)

        ui.release()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("ordinary:open", "ordinary:close"), rig.headset.events)
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
        assertNull(runtime.integration.value.owner.holder)
        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.selected)

        // The earphones keep their audio connection and connect again later; a cached process must not react.
        rig.appTransport.audioDevice.emit(null)
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, rig.headset.opens("ordinary"))
        assertEquals(ConnectionState.Disconnected, runtime.state.value.connection)
    }

    @Test
    fun withBackgroundControlOnTheServiceKeepsOneConnectionAcrossInterfaceCloseAndReopen() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        runtime.updateIntegrationSettings(runtime.integration.value.settings.copy(backgroundControlEnabled = true))
        val service = runtime.holdOrdinarySession()
        var ui = runtime.holdOrdinarySession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        val session = assertNotNull(runtime.state.value.device).sessionId
        val generation = assertNotNull(runtime.integration.value.owner.holder).generation

        ui.release()
        runCurrent()
        advanceUntilIdle()
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(listOf("ordinary:open"), rig.headset.events)

        ui = runtime.holdOrdinarySession()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("ordinary:open"), rig.headset.events)
        assertEquals(session, assertNotNull(runtime.state.value.device).sessionId)
        assertEquals(generation, assertNotNull(runtime.integration.value.owner.holder).generation)

        // Turning the intent off ends the service; with the interface gone too the connection ends.
        service.release()
        ui.release()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("ordinary:open", "ordinary:close"), rig.headset.events)
    }

    @Test
    fun repeatedHoldsShareOneSessionAndOnlyTheLastReleaseEndsIt() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val ui = runtime.holdOrdinarySession()
        val service = runtime.holdOrdinarySession()
        val wake = runtime.holdOrdinarySession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        val generation = assertNotNull(runtime.integration.value.owner.holder).generation

        // Holds taken while a session exists and an idempotent double release change nothing.
        val again = runtime.holdOrdinarySession()
        assertEquals(generation, assertNotNull(runtime.integration.value.owner.holder).generation)
        ui.release()
        ui.release()
        runCurrent()
        service.release()
        wake.release()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("ordinary:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)

        again.release()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("ordinary:open", "ordinary:close"), rig.headset.events)
    }

    @Test
    fun releasingAndHoldingAgainBeforeTheSessionEndedDoesNotLoseTheSession() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val first = runtime.holdOrdinarySession()
        runCurrent()

        first.release()
        val second = runtime.holdOrdinarySession()
        runCurrent()
        advanceUntilIdle()

        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNotNull(runtime.integration.value.owner.holder)
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("ordinary:open"), rig.headset.events)
        second.release()
    }

    @Test
    fun moduleControlNeverCreatesTheOrdinarySessionWhateverHoldsExist() = runTest {
        val module = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        module.bootHost()
        val runtime = module.newRuntime(interfaceOpen = false)
        runtime.holdOrdinarySession()
        runtime.holdOrdinarySession()
        module.appTransport.audioDevice.emit(module.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertTrue(module.appTransport.links.isEmpty())
        assertEquals(0, module.headset.opens("ordinary"))
        assertTrue(runtime.integration.value.owner.holder?.way != ControlWay.ORDINARY)

        // Switching to module control ends the ordinary session although holds remain, and new holds add none.
        val rig = OwnerRig(this)
        rig.bootHost()
        val held = rig.newRuntime()
        runCurrent()
        assertIs<HandoffOutcome.Completed>(held.selectControlWay(ControlWay.MODULE))
        runCurrent()
        held.holdOrdinarySession()
        runCurrent()
        advanceUntilIdle()
        assertEquals(ControlWay.MODULE, held.integration.value.owner.selected)
        assertTrue(held.integration.value.owner.holder?.way != ControlWay.ORDINARY)
        assertTrue(rig.appTransport.links.isEmpty())

        // Returning to ordinary control starts the session because the holds are still open.
        assertIs<HandoffOutcome.Completed>(held.selectControlWay(ControlWay.ORDINARY))
        runCurrent()
        assertEquals(ControlWay.ORDINARY, held.integration.value.owner.holder?.way)
    }

    @Test
    fun nothingHoldsTheSessionBeforeFirstUnlockAndTheHoldsTakeEffectAtUnlock() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(userUnlocked = false, interfaceOpen = false)
        val hold = runtime.holdOrdinarySession()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceUntilIdle()
        assertTrue(rig.appTransport.links.isEmpty())

        runtime.onUserUnlocked(HostSettings())
        runCurrent()
        advanceUntilIdle()

        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
        assertEquals(listOf("ordinary:open"), rig.headset.events)
        hold.release()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("ordinary:open", "ordinary:close"), rig.headset.events)
    }
}

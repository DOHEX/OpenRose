package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A reference names one device session of one owner. Each hold cycle builds a new ordinary session and a
 * module owner builds its own, so a reference from a released owner must never match the next one.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionReferenceOwnershipTest {
    private fun SwitchingControlRuntime.currentReference(): SessionReference {
        val device = requireNotNull(state.value.device)
        return SessionReference(device.deviceId, device.sessionId)
    }

    @Test
    fun aReferenceFromAReleasedOrdinarySessionIsRefusedByTheNextOneWithoutTouchingTheLink() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val id = rig.earphones.deviceId
        val first = runtime.holdOrdinarySession()
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        val oldReference = runtime.currentReference()
        val oldPrompt = requireNotNull(runtime.integration.value.presentation.connectionPrompt).id
        first.release()
        runCurrent()
        assertEquals(null, runtime.state.value.device)

        val second = runtime.holdOrdinarySession()
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        val current = runtime.currentReference()
        assertNotEquals(oldReference, current)
        val writes = rig.appTransport.links.sumOf { it.writes.size }

        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(oldReference))
        assertIs<OperationResult.Failure>(runtime.set(oldReference, DeviceSetting.NOISE_MODE, FieldValue.Text("anc")))
        assertFalse(runtime.claimConnectionPrompt(oldReference, oldPrompt))

        assertEquals(writes, rig.appTransport.links.sumOf { it.writes.size }, "a stale reference reaches no earphones")
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(current))
        second.release()
    }

    @Test
    fun aReferenceNeverCrossesBetweenTheOrdinaryAndTheModuleOwner() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        val id = rig.earphones.deviceId
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        val ordinaryReference = runtime.currentReference()

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        val moduleReference = runtime.currentReference()
        assertNotEquals(ordinaryReference.sessionId, moduleReference.sessionId)
        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(ordinaryReference))
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(moduleReference))

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.ORDINARY))
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        val laterReference = runtime.currentReference()
        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(ordinaryReference))
        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(moduleReference))
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(laterReference))
    }
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionReferenceOwnershipTest {
    private fun SwitchingControlRuntime.currentReference(): SessionReference {
        val device = requireNotNull(state.value.device)
        return SessionReference(device.deviceId, device.sessionId)
    }

    @Test
    fun aReferenceFromAReleasedAppSessionIsRefusedByTheNextOneWithoutTouchingTheLink() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime(interfaceOpen = false)
        val id = rig.earphones.deviceId
        val first = runtime.holdAppSession()
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        rig.eventually("a ready session with its prompt") { runtime.state.value.readySession != null && runtime.integration.value.presentation.prompt != null }
        val oldReference = runtime.currentReference()
        val oldPrompt = requireNotNull(runtime.integration.value.presentation.prompt).id
        first.release()
        rig.eventually("the released session to end") { runtime.state.value.device == null }
        assertEquals(null, runtime.state.value.device)

        val second = runtime.holdAppSession()
        rig.eventually("the app owner to take over again") { runtime.integration.value.owner.readiness == OwnerReadiness.Ready }
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        rig.eventually("a ready session") { runtime.state.value.readySession != null }
        val current = runtime.currentReference()
        assertNotEquals(oldReference, current)
        val writes = rig.app.writes

        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(oldReference))
        assertIs<OperationResult.Failure>(runtime.set(oldReference, HeadsetCommand.NoiseMode(NoiseMode.ANC)))
        assertFalse(runtime.claimPrompt(oldReference, oldPrompt))

        assertEquals(writes, rig.app.writes, "a stale reference reaches no earphones")
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(current))
        second.release()
    }

    @Test
    fun aReferenceNeverCrossesBetweenTheAppAndTheModuleOwner() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        val id = rig.earphones.deviceId
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        rig.eventually("a ready app session") { runtime.state.value.readySession != null }
        val appReference = runtime.currentReference()

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        rig.eventually("a ready module session") { runtime.state.value.readySession?.let { it.sessionId != appReference.sessionId } == true }
        val moduleReference = runtime.currentReference()
        assertNotEquals(appReference.sessionId, moduleReference.sessionId)
        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(appReference))
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(moduleReference))

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.APP))
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        rig.eventually("a ready app session") { runtime.state.value.readySession?.let { it.sessionId != moduleReference.sessionId } == true }
        val laterReference = runtime.currentReference()
        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(appReference))
        assertIs<SessionSnapshotResult.Failure>(runtime.readSession(moduleReference))
        assertIs<SessionSnapshotResult.Available>(runtime.readSession(laterReference))
    }
}

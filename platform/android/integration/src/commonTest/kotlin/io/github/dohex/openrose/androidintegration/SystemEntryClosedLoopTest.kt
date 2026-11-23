package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fusion device center and the system headset entries as callers of the module host over real earphone
 * replies: a command is a success only when the earphones confirm it, every refusal stays observable, and
 * nothing a revoked owner produced can still be read or driven.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemEntryClosedLoopTest {
    private class Entry(val rig: OwnerRig, val lease: String, val generation: OwnerGeneration, val reference: SessionReference)

    private suspend fun TestScope.connectedEntry(identity: CallerIdentity = milinkIdentity): Entry {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.bootHost()
        runCurrent()
        testScheduler.advanceUntilIdle()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(identity) as HostOpenResult.Opened).lease.id
        val connection = assertIs<ConnectionState.Connected>(requireNotNull(rig.host.control.view(identity, lease)).state.connection)
        rig.hostTransport.links.single().initializing = false
        return Entry(rig, lease, generation, SessionReference(connection.deviceId, connection.sessionId))
    }

    private fun Entry.noise(mode: String) = HostCommand.Set(reference, HeadsetCommand.NoiseMode(requireNotNull(NoiseMode.fromId(mode))))

    private suspend fun Entry.mappedMode(): Int? {
        val read = assertIs<HostReadResult.Available>(rig.host.control.readSession(milinkIdentity, lease, generation, reference))
        return SystemHeadsetMapping.miLinkAncState(assertIs<SessionSnapshotResult.Available>(read.snapshot).device.diagnostics.fields)
    }

    private fun failureOf(result: HostCallResult) = assertIs<OperationResult.Failure>(assertIs<HostCallResult.Operation>(result).result).error.code

    @Test
    fun aFusionCenterNoiseTapSucceedsOnlyWhenTheEarphonesConfirmIt() = runTest {
        val entry = connectedEntry()
        assertEquals(0, entry.mappedMode())

        val tap = async { entry.rig.host.control.execute(milinkIdentity, entry.lease, entry.generation, entry.noise("anc")) }
        runCurrent()

        assertFalse(tap.isCompleted)
        assertEquals(0, entry.mappedMode())
        entry.rig.hostTransport.links.single().deviceReply("09ff00000106020e000100000020")
        runCurrent()

        assertEquals(HostCallResult.Operation(OperationResult.Success), tap.await())
        assertEquals(1, entry.mappedMode())
    }

    @Test
    fun aCommandFromAnotherCallerOrBeyondTheNoiseRoleIsUnauthorised() = runTest {
        val entry = connectedEntry()

        val stolen = entry.rig.host.control.execute(strangerIdentity, entry.lease, entry.generation, entry.noise("anc"))
        assertEquals(HostRejection.INVALID_LEASE, assertIs<HostCallResult.Rejected>(stolen).rejection)
        val beyond = entry.rig.host.control.execute(milinkIdentity, entry.lease, entry.generation, HostCommand.Disconnect)
        assertEquals(HostRejection.FORBIDDEN, assertIs<HostCallResult.Rejected>(beyond).rejection)
        assertIs<ConnectionState.Connected>(entry.rig.host.control.view(milinkIdentity, entry.lease)!!.state.connection)
    }

    @Test
    fun theEarphonesLosingTheLinkRevokesTheReferenceAndNeverFakesTheState() = runTest {
        val entry = connectedEntry()
        val app = (entry.rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        entry.rig.host.control.execute(appIdentity, app, entry.generation, HostCommand.Disconnect)
        runCurrent()

        val read = assertIs<HostReadResult.Available>(entry.rig.host.control.readSession(milinkIdentity, entry.lease, entry.generation, entry.reference))
        assertIs<SessionSnapshotResult.Failure>(read.snapshot)
        assertIs<ConnectionState.Disconnected>(requireNotNull(entry.rig.host.control.view(milinkIdentity, entry.lease)).state.connection)
        assertEquals(ControlErrorCode.DISCONNECTED, failureOf(entry.rig.host.control.execute(milinkIdentity, entry.lease, entry.generation, entry.noise("anc"))))
    }

    @Test
    fun switchingToOrdinaryControlRevokesTheCardAndItsReferencesThenAFreshSessionIsReadAfterSwitchingBack() = runTest {
        val entry = connectedEntry()
        val app = (entry.rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        val ended = async { entry.rig.host.control.changes(milinkIdentity, entry.lease, entry.generation).collect { } }
        runCurrent()

        val released = entry.rig.host.release(appIdentity, app, SwitchRequest("to-ordinary", ControlWay.MODULE, ControlWay.ORDINARY, entry.generation))
        assertIs<ReleaseResult.Released>(released)
        runCurrent()

        assertTrue(ended.isCompleted, "the system entry's watch ends with the owner generation")
        assertNull(entry.rig.host.control.view(milinkIdentity, entry.lease))
        assertEquals(
            HostRejection.NO_OWNER,
            assertIs<HostReadResult.Rejected>(
                entry.rig.host.control.readSession(milinkIdentity, entry.lease, entry.generation, entry.reference),
            ).rejection,
        )

        val freshApp = assertIs<HostOpenResult.Opened>(entry.rig.host.open(appIdentity)).lease.id
        val acquired = entry.rig.host.acquire(appIdentity, freshApp, SwitchRequest("to-module", ControlWay.ORDINARY, ControlWay.MODULE))
        val next = assertIs<AcquireResult.Acquired>(acquired).generation
        runCurrent()
        testScheduler.advanceUntilIdle()

        assertNotEquals(entry.generation, next)
        // The old generation can never be read or driven again, even though the device is the same.
        assertEquals(
            HostRejection.STALE_GENERATION,
            assertIs<HostReadResult.Rejected>(
                entry.rig.host.control.readSession(milinkIdentity, entry.lease, entry.generation, entry.reference),
            ).rejection,
        )
        // The new generation is read from the new session: real fields, read again from the earphones.
        val connection = assertIs<ConnectionState.Connected>(requireNotNull(entry.rig.host.control.view(milinkIdentity, entry.lease)).state.connection)
        val fresh = assertIs<HostReadResult.Available>(
            entry.rig.host.control.readSession(milinkIdentity, entry.lease, next, SessionReference(connection.deviceId, connection.sessionId)),
        )
        val device = assertIs<SessionSnapshotResult.Available>(fresh.snapshot).device
        assertEquals(2, entry.rig.hostTransport.links.size, "the new owner opened its own link")
        assertTrue(device.diagnostics.fields.containsKey(DeviceField.LEFT_BATTERY))
    }

    private suspend fun TestScope.frameWritten(offAsWind: Boolean, mode: String): String {
        val entry = connectedEntry()
        entry.rig.host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE, fusionOffAsWind = offAsWind))) }
        runCurrent()
        val link = entry.rig.hostTransport.links.single()
        val before = link.writes.size
        val tap = async { entry.rig.host.control.execute(milinkIdentity, entry.lease, entry.generation, entry.noise(mode)) }
        runCurrent()
        testScheduler.advanceUntilIdle()
        tap.await()
        return link.writes.drop(before).first()
    }

    @Test
    fun anOffTapWritesWindReductionOnlyWhenTheUserChoseIt() = runTest {
        val wind = frameWritten(offAsWind = false, mode = "wind")
        val normal = frameWritten(offAsWind = false, mode = "normal")
        assertNotEquals(wind, normal)

        assertEquals(wind, frameWritten(offAsWind = true, mode = "normal"))
        assertEquals(frameWritten(offAsWind = false, mode = "anc"), frameWritten(offAsWind = true, mode = "anc"))
    }
}

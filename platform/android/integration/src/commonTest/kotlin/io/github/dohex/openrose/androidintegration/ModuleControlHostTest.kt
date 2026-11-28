package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.HostSnapshotFormatException
import io.github.dohex.openrose.androidintegration.wire.Wire
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.androidintegration.wire.str
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import io.github.dohex.openrose.ffi.kotlincore.toControlState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModuleControlHostTest {
    @Test
    fun unreadablePreferencesPreserveTheOwnerAndCannotAuthorizeANewAcquire() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        val before = assertIs<HostStatus.Active>(rig.host.status.value)
        val lease = assertIs<HostOpenResult.Opened>(rig.host.open(appIdentity)).lease

        assertFalse(rig.host.configure { null })
        assertEquals(before, rig.host.status.value)
        assertIs<AcquireResult.Refused>(rig.host.acquire(appIdentity, lease.id, SwitchRequest("new", ControlWay.APP, ControlWay.MODULE)))

        assertTrue(rig.host.configure { HostConfiguration(true, HostSettings()) })
        assertIs<HostStatus.Inactive>(rig.host.status.value)
    }

    @Test
    fun aCallerThatIsNotOpenRoseOrASystemEntryIsRefusedAtAuthorisation() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()

        val refused = assertIs<HostOpenResult.Refused>(rig.host.open(strangerIdentity))

        assertEquals(HostRejection.UNAUTHORIZED, refused.rejection)
        rig.link.identity = strangerIdentity
        val opened = rig.link.open()
        assertEquals(OwnerFailureStage.AUTHORIZATION, assertIs<LinkOpenResult.Failed>(opened).stage)
    }

    @Test
    fun aLeaseCannotBeReplayedByAnotherCallerOrAfterItIsClosed() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        runCurrent()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id

        val sameUidOtherProcess = appIdentity.copy(pid = 1)
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(sameUidOtherProcess, lease, generation, HostCommand.StartScan)).rejection,
        )
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(strangerIdentity, lease, generation, HostCommand.StartScan)).rejection,
        )
        rig.host.close(appIdentity, lease)
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(appIdentity, lease, generation, HostCommand.StartScan)).rejection,
        )
    }

    @Test
    fun aSystemEntryMayOnlyDriveNoiseControlOfAKnownSessionAndClaimPrompts() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        runCurrent()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(milinkIdentity) as HostOpenResult.Opened).lease.id
        val reference = SessionReference(rig.earphones.deviceId, 1)

        for (command in listOf(
            HostCommand.SelectCurrentHeadset(rig.earphones.deviceId),
            HostCommand.Connect(rig.earphones.deviceId),
            HostCommand.Disconnect,
            HostCommand.StartScan,
            HostCommand.Set(null, HeadsetCommand.NoiseMode(NoiseMode.ANC)),
            HostCommand.Set(reference, HeadsetCommand.EqualizerPreset("pop")),
        )) {
            val rejected = assertIs<HostCallResult.Rejected>(rig.host.control.execute(milinkIdentity, lease, generation, command))
            assertEquals(HostRejection.FORBIDDEN, rejected.rejection, command.toString())
        }
        val noise = rig.host.control.execute(milinkIdentity, lease, generation, HostCommand.Set(reference, HeadsetCommand.NoiseMode(NoiseMode.ANC)))
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(assertIs<HostCallResult.Operation>(noise).result).error.code)
        assertTrue(rig.host.control.frames(milinkIdentity, lease, generation).toList().isEmpty())
        val release = rig.host.release(milinkIdentity, lease, SwitchRequest("x", ControlWay.MODULE, ControlWay.APP))
        assertEquals(HostRejection.FORBIDDEN, assertIs<ReleaseResult.Refused>(release).rejection)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun aCommandForAnEarlierOwnerGenerationIsRejected() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        runCurrent()
        val first = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id

        val released = rig.host.release(appIdentity, lease, SwitchRequest("a", ControlWay.MODULE, ControlWay.APP, first))
        assertEquals(first, assertIs<ReleaseResult.Released>(released).receipt.generation)
        assertEquals(
            HostRejection.NO_OWNER,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(appIdentity, lease, first, HostCommand.StartScan)).rejection,
        )
        val request = SwitchRequest("b", ControlWay.APP, ControlWay.MODULE)
        assertEquals(HostRejection.INVALID_LEASE, assertIs<AcquireResult.Refused>(rig.host.acquire(appIdentity, lease, request)).rejection)
        val nextLease = assertIs<HostOpenResult.Opened>(rig.host.open(appIdentity)).lease.id
        val acquired = rig.host.acquire(appIdentity, nextLease, request)
        val second = assertIs<AcquireResult.Acquired>(acquired).generation

        assertNotEquals(first, second)
        assertEquals(
            HostRejection.STALE_GENERATION,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(appIdentity, lease, first, HostCommand.StartScan)).rejection,
        )
        assertIs<HostCallResult.Operation>(rig.host.control.execute(appIdentity, lease, second, HostCommand.StopScan))
        val stale = rig.host.release(appIdentity, lease, SwitchRequest("c", ControlWay.MODULE, ControlWay.APP, first))
        assertEquals(HostRejection.STALE_GENERATION, assertIs<ReleaseResult.Refused>(stale).rejection)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun aReleaseThatNamesNoOwnerGenerationWhileAnOwnerHoldsItIsRefusedAndKeepsTheOwner() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        runCurrent()
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id

        val refused = rig.host.release(appIdentity, lease, SwitchRequest("a", ControlWay.MODULE, ControlWay.APP))

        assertEquals(HostRejection.STALE_GENERATION, assertIs<ReleaseResult.Refused>(refused).rejection)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun theHostFollowsTheSavedWayAndDoesNotReclaimAfterItIsLeft() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        assertIs<HostStatus.Inactive>(rig.host.status.value)

        rig.host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        runCurrent()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        rig.host.control.execute(appIdentity, lease, generation, HostCommand.ConnectFromSystem(rig.earphones.deviceId))
        runCurrent()
        assertEquals(listOf("module:open"), rig.headset.events)

        rig.host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.APP))) }
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertIs<HostStatus.Inactive>(rig.host.status.value)
        rig.hostHeadset.connectAudio(rig.earphones.deviceId)
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
    }

    @Test
    fun aSettingThroughTheModuleIsConfirmedByTheEarphonesNotByReachingTheHost() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        rig.eventually("the host link to be ready") { runtime.state.value.readySession != null }
        val link = rig.hostHeadset.links.single()
        link.initializing = false

        val writesBefore = link.writes.size
        val pending = async { runtime.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL)) }
        runCurrent()
        rig.eventually("the setting to be written") { link.writes.size > writesBefore }
        advanceTimeBy(rig.clientTiming.statusPollMillis)
        runCurrent()

        assertFalse(pending.isCompleted)
        rig.eventually("the host frame to show the setting written") {
            runtime.state.value.settingOperations.lastOrNull()?.stage == SettingStage.WRITTEN
        }
        assertEquals(FieldValue.Text("wind"), noiseMode(runtime))
        link.deviceReply("09ff00000106020e000001000020")
        runCurrent()

        assertEquals(OperationResult.Success, pending.await())
        assertEquals(SettingStage.CONFIRMED, runtime.state.value.settingOperations.last().stage)
        assertEquals(FieldValue.Text("normal"), noiseMode(runtime))
    }

    @Test
    fun aLostHostLinkNeverTurnsAPendingSettingIntoSuccess() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        rig.eventually("the host link to be ready") { runtime.state.value.readySession != null }
        rig.hostHeadset.links.single().initializing = false

        val pending = async { runtime.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL)) }
        runCurrent()
        rig.link.connections.last { it.isOpen }.sever(processDied = false)
        runCurrent()
        rig.link.reachable = false
        rig.hostHeadset.links.single().deviceReply("09ff00000106020e000001000020")
        runCurrent()

        val result = assertIs<OperationResult.Failure>(pending.await())
        assertEquals(ControlErrorCode.DISCONNECTED, result.error.code)
        assertTrue(runtime.state.value.device == null)
        assertIs<OwnerReadiness.Unavailable>(runtime.integration.value.owner.readiness)
    }

    @Test
    fun appControlStateThroughTheHostFrameBytePathEqualsTheRustSnapshot() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        rig.eventually("the host link to be ready") { runtime.state.value.readySession != null }
        assertEquals(OperationResult.Success, runtime.set(HeadsetCommand.NoiseMode(NoiseMode.NORMAL)))
        runCurrent()

        val rustSnapshot = requireNotNull(rig.hostCore).coreState.value
        assertTrue(rustSnapshot.headset != null)
        assertEquals(rustSnapshot.toControlState(), runtime.state.value)

        val generation = requireNotNull(runtime.integration.value.owner.holder).generation
        val connection = assertIs<LinkOpenResult.Open>(rig.link.open()).connection
        try {
            assertEquals(rustSnapshot, connection.frames(generation).first().snapshot)
        } finally {
            connection.close()
        }
    }

    @Test
    fun malformedAndUnsupportedVersionFrameBytesThroughOwnerRigTurnIntoALostLink() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        rig.eventually("the module owner to become ready") { runtime.integration.value.owner.readiness == OwnerReadiness.Ready }
        val generation = requireNotNull(runtime.integration.value.owner.holder).generation

        fun tamperSnapshot(raw: String, transform: (ByteArray) -> ByteArray): String {
            val obj = parseWire(raw).asObj()
            val updated = transform(Base64.decode(obj.str("snapshot")))
            return Wire.Obj(obj.fields + ("snapshot" to str(Base64.encode(updated)))).render()
        }

        val connection = assertIs<LinkOpenResult.Open>(rig.link.open()).connection
        try {
            rig.link.tamperFrame = { raw -> tamperSnapshot(raw) { "corrupt".encodeToByteArray() } }
            val malformedLink = assertFailsWith<HostLinkException> { connection.frames(generation).first() }
            val malformedFormat = assertIs<HostSnapshotFormatException>(
                generateSequence(malformedLink.cause) { it.cause }.filterIsInstance<HostSnapshotFormatException>().firstOrNull(),
            )
            assertIs<CoreDecodeResult.Error.Malformed>(malformedFormat.error)

            rig.link.tamperFrame = { raw ->
                tamperSnapshot(raw) { bytes ->
                    bytes.copyOf().apply { this[3] = (coreWireFormatVersion().toInt() + 1).toByte() }
                }
            }
            val unsupportedLink = assertFailsWith<HostLinkException> { connection.frames(generation).first() }
            val unsupportedFormat = assertIs<HostSnapshotFormatException>(
                generateSequence(unsupportedLink.cause) { it.cause }.filterIsInstance<HostSnapshotFormatException>().firstOrNull(),
            )
            val unsupported = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(unsupportedFormat.error)
            assertEquals(coreWireFormatVersion(), unsupported.expected)
            assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), unsupported.actual)
        } finally {
            connection.close()
        }

        rig.host.updatePlatform(grantedAndroidPlatform.copy(scanPermission = PermissionState.DENIED))
        rig.eventually("the owner link to report unavailable after tampered frame") {
            runtime.integration.value.owner.readiness is OwnerReadiness.Unavailable
        }
        val readiness = assertIs<OwnerReadiness.Unavailable>(runtime.integration.value.owner.readiness)
        assertEquals(OwnerFailureStage.CONNECTION, readiness.stage)
    }

    private fun noiseMode(runtime: ControlRuntime): FieldValue = assertIs<FieldReading.Known>(runtime.state.value.device?.diagnostics?.fields?.get(DeviceField.NOISE_MODE)).value

    private fun kotlinx.coroutines.test.TestScope.advanceUntilIdleQuietly() = testScheduler.advanceUntilIdle()
}

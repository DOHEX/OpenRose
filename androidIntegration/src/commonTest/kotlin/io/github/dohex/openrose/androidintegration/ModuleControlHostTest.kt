package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.ControlWire
import io.github.dohex.openrose.androidintegration.wire.HostPlatformFacts
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The host side of the seam: authorisation, owner generations, unlock gating and device-confirmed settings. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModuleControlHostTest {
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
            assertIs<HostCallResult.Rejected>(rig.host.execute(sameUidOtherProcess, lease, generation, HostCommand.StartScan)).rejection,
        )
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.execute(strangerIdentity, lease, generation, HostCommand.StartScan)).rejection,
        )
        rig.host.close(appIdentity, lease)
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.execute(appIdentity, lease, generation, HostCommand.StartScan)).rejection,
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

        fun forbidden(command: HostCommand) = HostRejection.FORBIDDEN
        for (command in listOf(
            HostCommand.Connect(rig.earphones.deviceId), HostCommand.Disconnect, HostCommand.StartScan,
            HostCommand.UpdateSettings(HostSettings()), HostCommand.Set(null, DeviceSetting.NOISE_MODE, FieldValue.Text("anc")),
            HostCommand.Set(reference, DeviceSetting.EQ, FieldValue.Text("pop")),
        )) {
            assertEquals(forbidden(command), assertIs<HostCallResult.Rejected>(rig.host.execute(milinkIdentity, lease, generation, command)).rejection, command.toString())
        }
        // Allowed in role; a reference to no current session is still refused by the runtime itself.
        val noise = rig.host.execute(milinkIdentity, lease, generation, HostCommand.Set(reference, DeviceSetting.NOISE_MODE, FieldValue.Text("anc")))
        assertEquals(ControlErrorCode.DISCONNECTED, assertIs<OperationResult.Failure>(assertIs<HostCallResult.Operation>(noise).result).error.code)
        assertTrue(rig.host.frames(milinkIdentity, lease, generation).toList().isEmpty())
        val release = rig.host.release(milinkIdentity, lease, SwitchRequest("x", ControlWay.MODULE, ControlWay.ORDINARY))
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

        val released = rig.host.release(appIdentity, lease, SwitchRequest("a", ControlWay.MODULE, ControlWay.ORDINARY, first))
        assertEquals(first, assertIs<ReleaseResult.Released>(released).receipt.generation)
        assertEquals(
            HostRejection.NO_OWNER,
            assertIs<HostCallResult.Rejected>(rig.host.execute(appIdentity, lease, first, HostCommand.StartScan)).rejection,
        )
        val acquired = rig.host.acquire(appIdentity, lease, SwitchRequest("b", ControlWay.ORDINARY, ControlWay.MODULE), HostSettings())
        val second = assertIs<AcquireResult.Acquired>(acquired).generation

        assertNotEquals(first, second)
        assertEquals(
            HostRejection.STALE_GENERATION,
            assertIs<HostCallResult.Rejected>(rig.host.execute(appIdentity, lease, first, HostCommand.StartScan)).rejection,
        )
        assertIs<HostCallResult.Operation>(rig.host.execute(appIdentity, lease, second, HostCommand.StopScan))
        val stale = rig.host.release(appIdentity, lease, SwitchRequest("c", ControlWay.MODULE, ControlWay.ORDINARY, first))
        assertEquals(HostRejection.STALE_GENERATION, assertIs<ReleaseResult.Refused>(stale).rejection)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun aReleaseThatNamesNoOwnerGenerationWhileAnOwnerHoldsItIsRefusedAndKeepsTheOwner() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        runCurrent()
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id

        val refused = rig.host.release(appIdentity, lease, SwitchRequest("a", ControlWay.MODULE, ControlWay.ORDINARY))

        assertEquals(HostRejection.STALE_GENERATION, assertIs<ReleaseResult.Refused>(refused).rejection)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun aHostWhoseCloseCannotBeConfirmedKeepsServingAndOwningUntilItIsClosed() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.bootHost()
        runCurrent()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        rig.hostTransport.closeFails = true

        assertFalse(rig.host.stop())

        assertIs<HostOpenResult.Opened>(rig.host.open(appIdentity))
        assertEquals(generation, (rig.host.status.value as HostStatus.Active).generation)
        val refused = rig.host.release(appIdentity, lease, SwitchRequest("a", ControlWay.MODULE, ControlWay.ORDINARY, generation))
        assertEquals(HostRejection.RELEASE_FAILED, assertIs<ReleaseResult.Refused>(refused).rejection)
        assertEquals("module", rig.headset.openOwner)

        rig.hostTransport.closeFails = false
        assertTrue(rig.host.stop())
        assertEquals(null, rig.headset.openOwner)
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.execute(appIdentity, lease, generation, HostCommand.StartScan)).rejection,
        )
    }

    @Test
    fun thereIsNoControlBeforeFirstUnlockAndTheExistingAudioConnectionIsReadAfterIt() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.bootHost(unlocked = false)
        runCurrent()

        assertIs<HostStatus.Inactive>(rig.host.status.value)
        assertEquals(emptyList(), rig.headset.events)

        rig.bootHost(unlocked = true)
        runCurrent()
        advanceUntilIdleQuietly()

        assertIs<HostStatus.Active>(rig.host.status.value)
        assertEquals(listOf("module:open"), rig.headset.events)
    }

    @Test
    fun theHostFollowsTheSavedWayAndDoesNotReclaimAfterItIsLeft() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        assertIs<HostStatus.Inactive>(rig.host.status.value)

        rig.host.configure(HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))))
        runCurrent()
        val generation = (rig.host.status.value as HostStatus.Active).generation
        val lease = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        rig.host.execute(appIdentity, lease, generation, HostCommand.ConnectFromSystem(rig.earphones.deviceId))
        runCurrent()
        assertEquals(listOf("module:open"), rig.headset.events)

        rig.host.configure(HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.ORDINARY))))
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertIs<HostStatus.Inactive>(rig.host.status.value)
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
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
        val link = rig.hostTransport.links.single()
        link.initializing = false

        val pending = async { runtime.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()

        assertFalse(pending.isCompleted)
        assertEquals(SettingStage.WRITTEN, runtime.state.value.settingOperations.last().stage)
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
        rig.hostTransport.links.single().initializing = false

        val pending = async { runtime.set(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")) }
        runCurrent()
        rig.link.connections.last { it.isOpen }.sever(processDied = false)
        runCurrent()
        rig.link.reachable = false
        rig.hostTransport.links.single().deviceReply("09ff00000106020e000001000020")
        runCurrent()

        val result = assertIs<OperationResult.Failure>(pending.await())
        assertEquals(ControlErrorCode.DISCONNECTED, result.error.code)
        // The stale reading is withdrawn instead of staying on screen as current.
        assertTrue(runtime.state.value.device == null)
        assertIs<OwnerReadiness.Unavailable>(runtime.integration.value.owner.readiness)
    }

    @Test
    fun theWireCarriesTheCompleteDeviceStateForBothModels() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val rig = OwnerRig(this, earphones = rosePair("rose-${profile.name}", profile))
            val runtime = LocalControlRuntime(
                PlatformState(PermissionState.GRANTED, TransportAvailability.Available), UserSettings(),
                rig.appTransport, backgroundScope, SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0),
            )
            assertEquals(OperationResult.Success, runtime.connect(rig.earphones.deviceId, pinned = true))
            val state = runtime.state.value

            val wire = ControlWire.encodeOwned(
                HostPlatformFacts(state.platform.permission, state.platform.transport), state,
            ).render()
            val decoded = ControlWire.decodeOwned(parseWire(wire))

            assertEquals(state.device, decoded.state.device)
            assertEquals(state.connection, decoded.state.connection)
            assertEquals(state.transportSelection, decoded.state.transportSelection)
            assertEquals(state.bondedDevices, decoded.state.bondedDevices)
            assertTrue(requireNotNull(decoded.state.device).capabilities.writableSettings.isNotEmpty())
            runtime.disconnect()
        }
    }

    private fun noiseMode(runtime: ControlRuntime): FieldValue =
        assertIs<FieldReading.Known>(runtime.state.value.device?.fields?.get(DeviceField.NOISE_MODE)).value

    private fun kotlinx.coroutines.test.TestScope.advanceUntilIdleQuietly() = testScheduler.advanceUntilIdle()
}

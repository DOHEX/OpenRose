package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * The unified runtime is the only seam: a real [SwitchingControlRuntime] over a real ordinary
 * [LocalControlRuntime][io.github.dohex.openrose.core.LocalControlRuntime] session, a real [ModuleControlHost] over its own real session, and only the
 * earphones' transport and the IPC link faked.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OwnerHandoffTest {
    @Test
    fun fusionEntryWaitsForTheFirstModuleFrameAndConnectsTheChosenHeadphones() = runTest {
        val playing = rosePair("playing")
        val chosen = rosePair("chosen").copy(classicAddress = "11:22:33:44:55:77")
        val rig = OwnerRig(this, playing, OwnerRecord(ControlWay.MODULE), listOf(playing, chosen))
        rig.hostTransport.audioDevice.emit(playing.deviceId)
        rig.bootHost()
        runCurrent()
        rig.link.openMillis = 300
        val runtime = rig.newRuntime()
        val request = async { runtime.connectFromSystem(chosen.deviceId) }
        runCurrent()
        assertFalse(request.isCompleted)

        assertEquals(OperationResult.Success, request.await())
        runCurrent()
        assertEquals(chosen.deviceId, runtime.state.value.readySession?.deviceId)
    }

    @Test
    fun scanPermissionDoesNotBlockPairedControl() = runTest {
        val rig = OwnerRig(this)
        val runtime = rig.newRuntime()
        runtime.updatePlatformState(
            PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Available, scanPermission = PermissionState.DENIED),
        )
        runCurrent()
        val denied = assertIs<OperationResult.Failure>(runtime.startScan())
        assertEquals(ControlErrorCode.PERMISSION_REQUIRED, denied.error.code)
        assertEquals(OperationResult.Success, runtime.connect(rig.earphones.deviceId))
    }

    @Test
    fun ordinaryToModuleReleasesTheOrdinaryConnectionBeforeTheHostConnects() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        val id = rig.earphones.deviceId
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)

        val outcome = runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()

        assertIs<HandoffOutcome.Completed>(outcome)
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        assertEquals(listOf("ordinary:open", "ordinary:close", "module:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        val owner = runtime.integration.value.owner
        assertEquals(ControlWay.MODULE, owner.selected)
        assertEquals(OwnerReadiness.Ready, owner.readiness)
        assertEquals(HOST_PACKAGE, owner.holder?.packageName)
        assertEquals(ControlWay.MODULE, owner.holder?.way)
        val device = requireNotNull(runtime.state.value.device)
        assertEquals(rig.earphones.deviceId, device.deviceId)
        val left = assertIs<FieldReading.Known>(device.diagnostics.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldValue.Number(100), left.value)
        assertEquals(1, rig.headset.opens("ordinary"))
        assertEquals(1, rig.headset.opens("module"))
    }

    @Test
    fun anAcquireArrivingAfterTimeoutCannotUndoALaterRelease() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        val id = rig.earphones.deviceId
        rig.appTransport.audioDevice.emit(id)
        rig.hostTransport.audioDevice.emit(id)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        rig.link.pauseNextAcquire = true
        assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.MODULE))
        val delayedCall: suspend () -> AcquireResult = requireNotNull(rig.link.delayedAcquire)

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.ORDINARY))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals("ordinary", rig.headset.openOwner)
        val lateResult = delayedCall.invoke()
        assertIs<AcquireResult.Refused>(lateResult)
        // Even a new lease has to honor the currently saved ordinary selection.
        val lease = assertIs<HostOpenResult.Opened>(rig.host.open(appIdentity)).lease.id
        val request = SwitchRequest("stale", ControlWay.ORDINARY, ControlWay.MODULE)
        assertIs<AcquireResult.Refused>(rig.host.acquire(appIdentity, lease, request))
        assertIs<HostStatus.Inactive>(rig.host.status.value)

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals("module", rig.headset.openOwner)
        assertEquals(0, rig.headset.overlappingOpens)
    }

    @Test
    fun moduleToOrdinaryClosesTheHostConnectionBeforeOrdinaryConnects() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("earlier", null)))
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        val id = rig.earphones.deviceId
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        assertEquals(listOf("module:open"), rig.headset.events)

        val outcome = runtime.selectControlWay(ControlWay.ORDINARY)
        runCurrent()
        assertIs<HandoffOutcome.Completed>(outcome)
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()

        assertEquals(listOf("module:open", "module:close", "ordinary:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertNull(rig.store.record.moduleRelease)
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
        assertIs<HostStatus.Inactive>(rig.host.status.value)
    }

    @Test
    fun anUnconfirmedReleaseBlocksTheTakeoverAndExplainsWhy() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        rig.link.releaseHangs = true

        val outcome = runtime.selectControlWay(ControlWay.ORDINARY)

        val blocked = assertIs<HandoffOutcome.Blocked>(outcome)
        assertEquals(OwnerFailureStage.RELEASE, blocked.stage)
        assertEquals(1, rig.headset.opens("module"))
        assertEquals(0, rig.headset.opens("ordinary"))
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.selected)
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.integration.value.owner.readiness)
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(runtime.connect(rig.earphones.deviceId)).error.code)
        assertFalse(runtime.disconnect(), "the unconfirmed module release still holds the connection")

        // Once the host can answer, the saved choice completes without another user action.
        rig.link.releaseHangs = false
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
    }

    @Test
    fun ordinaryControlNeverStartsWhileTheModuleCannotBeProvenReleased() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        rig.link.reachable = false

        val outcome = runtime.selectControlWay(ControlWay.ORDINARY)

        assertIs<HandoffOutcome.Blocked>(outcome)
        assertTrue(rig.appTransport.links.isEmpty())
        assertNotEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNotNull(rig.store.record.moduleRelease)
    }

    @Test
    fun observedEndOfTheHostProcessIsProofButAMereUnbindIsNot() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        rig.link.connections.last().sever(processDied = false)
        runCurrent()
        rig.link.reachable = false

        assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.ORDINARY))
        assertEquals(0, rig.headset.opens("ordinary"))

        rig.link.reachable = true
        rig.link.releaseKillsHostProcess = true
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()

        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNull(rig.store.record.moduleRelease)
    }

    @Test
    fun anUnavailableModuleKeepsModuleControlAndRecoversWithoutFallingBackToOrdinary() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        rig.link.reachable = false

        val outcome = runtime.selectControlWay(ControlWay.MODULE)

        val blocked = assertIs<HandoffOutcome.Blocked>(outcome)
        assertEquals(OwnerFailureStage.DISCOVERY, blocked.stage)
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()
        val owner = runtime.integration.value.owner
        assertEquals(ControlWay.MODULE, owner.selected)
        assertEquals(OwnerFailureStage.DISCOVERY, assertIs<OwnerReadiness.Unavailable>(owner.readiness).stage)
        assertEquals(ModuleUnavailableCause.HOST_NOT_FOUND, assertIs<ModuleAvailability.Unavailable>(runtime.integration.value.platform.module).cause)
        assertNull(owner.holder)
        assertTrue(rig.appTransport.links.isEmpty())

        rig.link.reachable = true
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(ControlWay.MODULE, runtime.integration.value.owner.selected)
        assertEquals(0, rig.headset.opens("ordinary"))
    }

    @Test
    fun aSavedChoiceThatCannotBePersistedChangesNothing() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        rig.store.failSaves = true

        val outcome = runtime.selectControlWay(ControlWay.MODULE)

        assertEquals(OwnerFailureStage.SELECTION_PERSISTENCE, assertIs<HandoffOutcome.Blocked>(outcome).stage)
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.selected)
        assertIs<ConnectionState.Connected>(runtime.state.value.connection)
        assertEquals(listOf("ordinary:open"), rig.headset.events)
    }

    @Test
    fun theSavedChoiceDecidesWhichOwnerStartsAfterARestart() = runTest {
        val module = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        module.bootHost()
        module.newRuntime()
        runCurrent()
        assertTrue(module.appTransport.links.isEmpty())
        assertEquals(0, module.headset.opens("ordinary"))

        val fresh = OwnerRig(this)
        fresh.bootHost()
        val ordinary = fresh.newRuntime()
        runCurrent()
        assertEquals(ControlWay.ORDINARY, ordinary.integration.value.owner.selected)
        assertEquals(OwnerReadiness.Ready, ordinary.integration.value.owner.readiness)
    }

    @Test
    fun recoveringAnInterruptedPublicationKeepsTheNextHostFromReacquiring() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE))
        val device = rig.earphones.deviceId
        rig.hostTransport.audioDevice.emit(device)
        rig.appTransport.audioDevice.emit(device)
        rig.bootHost()
        runCurrent()
        assertEquals(listOf("module:open"), rig.headset.events)

        // The application was killed between the local commit and publication to the framework.
        val generation = assertIs<HostStatus.Active>(rig.host.status.value).generation
        rig.store.record = OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease("switch", generation))
        rig.store.publicationUnavailable = true
        val runtime = rig.newRuntime()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()
        assertEquals(ControlWay.MODULE, rig.store.hostSelection)
        val blocked = assertIs<OwnerReadiness.SwitchBlocked>(runtime.integration.value.owner.readiness)
        assertEquals(OwnerFailureStage.SELECTION_PERSISTENCE, blocked.stage)
        assertNotNull(rig.store.record.moduleRelease)
        assertEquals(0, rig.headset.opens("ordinary"))

        // A refused change back to module control must not cancel recovery of the saved choice.
        rig.store.failSaves = true
        assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.MODULE))
        rig.store.failSaves = false
        rig.store.publicationUnavailable = false
        advanceTimeBy(rig.clientTiming.retryMillis)
        runCurrent()
        assertEquals(ControlWay.ORDINARY, rig.store.hostSelection)
        assertNull(rig.store.record.moduleRelease)
        assertEquals(listOf("module:open", "module:close", "ordinary:open"), rig.headset.events)

        rig.replaceHost(processDied = true)
        rig.bootHost()
        runCurrent()
        assertIs<HostStatus.Inactive>(rig.host.status.value)
        assertEquals(1, rig.headset.opens("module"))
        assertEquals(0, rig.headset.overlappingOpens)
    }

    @Test
    fun aRestartThatCannotConfirmTheModuleReleaseKeepsOrdinaryControlBlocked() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease("r", OwnerGeneration("host-0", 4))))
        rig.bootHost()
        rig.link.reachable = false
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)

        assertIs<OwnerReadiness.SwitchBlocked>(runtime.integration.value.owner.readiness)
        assertTrue(rig.appTransport.links.isEmpty())

        // A later host incarnation that holds nothing proves the named earlier owner is gone.
        rig.link.reachable = true
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNull(rig.store.record.moduleRelease)
    }

    @Test
    fun reopeningTheInterfaceAndChangingPreferencesNeverCreatesASecondConnection() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        val connectionBefore = runtime.state.value.connection

        repeat(3) {
            val observer = async { runtime.state.value }
            runtime.updateSettings(runtime.state.value.settings.copy(forceBle = true))
            runtime.updateIntegrationSettings(runtime.integration.value.settings.copy(fusionOffAsWind = true))
            runtime.refreshBondedDevices()
            runCurrent()
            observer.await()
        }
        advanceUntilIdle()

        assertEquals(1, rig.headset.opens("module"))
        assertEquals(connectionBefore, runtime.state.value.connection)
        assertEquals(1, rig.link.connections.count { it.isOpen })
        assertEquals(ControlWay.MODULE, runtime.integration.value.settings.controlWay)
        assertTrue(runtime.state.value.settings.forceBle)
        assertTrue(runtime.integration.value.settings.fusionOffAsWind)
    }

    @Test
    fun switchingAndObservingShareOneAsynchronousBinding() = runTest {
        val rig = OwnerRig(this)
        rig.link.openMillis = 30
        rig.bootHost()
        val runtime = rig.newRuntime()

        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        assertEquals(1, rig.link.opens)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
    }

    @Test
    fun aPreferenceUpdateCannotMoveTheOwnerBackToOrdinary() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        runtime.updateIntegrationSettings(IntegrationSettings(controlWay = ControlWay.ORDINARY, islandMode = IslandMode.OFF))
        runCurrent()
        val saved = runtime.integration.value.settings
        assertEquals(ControlWay.MODULE, saved.controlWay)
        val hostView = requireNotNull(rig.host.ownerView.first())
        assertEquals(IslandMode.OFFICIAL, hostView.settings.islandMode)
        rig.host.configure { HostConfiguration(true, HostSettings(integration = saved)) }
        assertEquals(IslandMode.OFF, requireNotNull(rig.host.ownerView.first()).settings.islandMode)
        assertEquals(ControlWay.MODULE, runtime.integration.value.owner.selected)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun nothingOwnsTheSessionBeforeFirstUnlockAndTheSavedWayDecidesAfterIt() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        val runtime = rig.newRuntime(userUnlocked = false)
        runCurrent()

        assertIs<OwnerReadiness.Idle>(runtime.integration.value.owner.readiness)
        assertTrue(rig.link.connections.isEmpty())
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(runtime.connectFromSystem(rig.earphones.deviceId)).error.code)
        assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.ORDINARY))
        assertEquals(ControlWay.MODULE, rig.store.record.selected)

        runtime.onUserUnlocked(HostSettings())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(ControlWay.MODULE, runtime.integration.value.owner.selected)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(0, rig.headset.opens("ordinary"))

        val ordinary = OwnerRig(this)
        val plain = ordinary.newRuntime(userUnlocked = false)
        runCurrent()
        assertTrue(ordinary.appTransport.links.isEmpty())
        plain.onUserUnlocked(HostSettings())
        runCurrent()
        assertEquals(OwnerReadiness.Ready, plain.integration.value.owner.readiness)
    }

    @Test
    fun ordinaryBackgroundControlIsNotInForceUnderModuleControlAndKeepsItsSavedIntent() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.updateIntegrationSettings(runtime.integration.value.settings.copy(backgroundControlEnabled = true))
        runtime.updatePlatformState(PlatformState(PermissionState.GRANTED, TransportAvailability.Available, hostPlatform = HostPlatform.ANDROID))
        runtime.updateIntegrationPlatform(
            IntegrationPlatformState(
                backgroundService = BackgroundServiceFacts(
                    wake = WakeFacts(WakeBand.ANDROID_8_11, CompanionAssociation.NOT_REQUIRED, PresenceObservation.NOT_REQUIRED),
                ),
            ),
        )
        assertEquals(BackgroundControlStatus.NotRunning, runtime.integration.value.backgroundControl(runtime.state.value))
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_8_11), runtime.integration.value.backgroundRecovery(runtime.state.value))

        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()

        assertEquals(BackgroundControlStatus.Disabled, runtime.integration.value.backgroundControl(runtime.state.value))
        assertEquals(BackgroundRecovery.Disabled, runtime.integration.value.backgroundRecovery(runtime.state.value))
        assertFalse(runtime.integration.value.mayStartBackgroundService(runtime.state.value))
        assertTrue(runtime.integration.value.settings.backgroundControlEnabled)

        runtime.selectControlWay(ControlWay.ORDINARY)
        runCurrent()
        assertEquals(BackgroundControlStatus.NotRunning, runtime.integration.value.backgroundControl(runtime.state.value))
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_8_11), runtime.integration.value.backgroundRecovery(runtime.state.value))
    }

    @Test
    fun aReceiptForAnotherRequestIsNotProofAndOrdinaryControlWaitsForARealOne() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        rig.link.tamperReceipt = { it.copy(requestId = "someone-else") }

        val blocked = assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.ORDINARY))

        assertEquals(OwnerFailureStage.RELEASE, blocked.stage)
        assertTrue(rig.appTransport.links.isEmpty())
        assertNotNull(rig.store.record.moduleRelease)
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.integration.value.owner.readiness)

        rig.link.tamperReceipt = { it }
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
        assertNull(rig.store.record.moduleRelease)
    }

    @Test
    fun aReceiptForAnotherOwnerGenerationIsNotProof() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        rig.link.tamperReceipt = { it.copy(generation = OwnerGeneration(it.incarnation, 99)) }

        val blocked = assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.ORDINARY))

        assertEquals(OwnerFailureStage.RELEASE, blocked.stage)
        assertTrue(rig.appTransport.links.isEmpty())
        assertNotNull(rig.store.record.moduleRelease)

        rig.link.tamperReceipt = { it }
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
    }

    @Test
    fun aStaleNamedGenerationIsNeverSilentlyRetargetedWithinTheSameRequest() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", OwnerGeneration("host-1", 7))))
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("module:open"), rig.headset.events)

        val blocked = assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.ORDINARY))

        // The request named an owner generation the host never had, so its answer proved nothing about the owner
        // that really held the connection, and ordinary control must not start on it.
        assertEquals(OwnerFailureStage.RELEASE, blocked.stage)
        assertTrue(rig.appTransport.links.isEmpty())
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.integration.value.owner.readiness)
        assertNotNull(rig.store.record.moduleRelease)

        // The owner the host reported is then named explicitly, and its receipt is checked against that name.
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNull(rig.store.record.moduleRelease)
    }

    @Test
    fun theEndOfAnotherHostIncarnationDoesNotProveTheNamedOwnerGone() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease("r", OwnerGeneration("host-0", 4))))
        rig.bootHost()
        rig.link.releaseKillsHostProcess = true
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()

        assertIs<OwnerReadiness.SwitchBlocked>(runtime.integration.value.owner.readiness)
        assertTrue(rig.appTransport.links.isEmpty())
        assertNotNull(rig.store.record.moduleRelease)

        // A later host incarnation that holds nothing is proof the earlier named owner is gone.
        rig.link.releaseKillsHostProcess = false
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertNull(rig.store.record.moduleRelease)
    }

    @Test
    fun repeatedSwitchingNeverOverlapsAndEveryRoundEndsWithOneOwner() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        val id = rig.earphones.deviceId
        val generations = mutableListOf<Long>()

        repeat(3) {
            assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
            runCurrent()
            assertEquals(ControlWay.MODULE, runtime.integration.value.owner.holder?.way)
            generations += (rig.host.status.value as HostStatus.Active).generation.sequence

            assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.ORDINARY))
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
            runCurrent()
            assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.holder?.way)
            assertIs<HostStatus.Inactive>(rig.host.status.value)
        }

        val expected = buildList {
            add("module:open")
            add("module:close")
            add("ordinary:open")
            repeat(2) {
                add("ordinary:close")
                add("module:open")
                add("module:close")
                add("ordinary:open")
            }
        }
        assertEquals(expected, rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(listOf(1L, 2L, 3L), generations)
        assertNull(rig.store.record.moduleRelease)
    }

    @Test
    fun anOrdinaryConnectionThatCannotBeConfirmedClosedBlocksTheTakeoverUntilItCloses() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        rig.appTransport.closeFails = true

        val blocked = assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.MODULE))

        assertEquals(OwnerFailureStage.RELEASE, blocked.stage)
        assertEquals(0, rig.headset.opens("module"))
        assertEquals(ControlWay.ORDINARY, rig.store.record.selected)
        assertEquals(ControlWay.ORDINARY, runtime.integration.value.owner.selected)

        rig.appTransport.closeFails = false
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        assertEquals(listOf("ordinary:open", "ordinary:close", "ordinary:open", "ordinary:close", "module:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
    }

    @Test
    fun bothModelsHandOffThroughTheSameContract() = runTest {
        for (profile in listOf(ModelProfile.I5_ZT, ModelProfile.I7_RS_V2)) {
            val rig = OwnerRig(this, earphones = rosePair("rose-${profile.name}", profile))
            rig.bootHost()
            val runtime = rig.newRuntime()
            runCurrent()
            val id = rig.earphones.deviceId
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id), profile.name)

            assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id), profile.name)
            runCurrent()
            val device = requireNotNull(runtime.state.value.device)
            assertEquals(profile, device.profile)
            assertEquals(ControlWay.MODULE, runtime.integration.value.owner.holder?.way)
            assertIs<FieldReading.Known>(device.diagnostics.fields[DeviceField.LEFT_BATTERY], profile.name)

            assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.ORDINARY))
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id), profile.name)
            runCurrent()
            assertEquals(listOf("ordinary:open", "ordinary:close", "module:open", "module:close", "ordinary:open"), rig.headset.events, profile.name)
            assertEquals(0, rig.headset.overlappingOpens, profile.name)
            assertEquals(profile, runtime.state.value.device?.profile)
        }
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}

package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The unified runtime is the only seam: a real [SwitchingControlRuntime] over a real ordinary
 * [ControlSession], a real [ModuleControlHost] over its own real session, and only the
 * earphones' transport and the IPC link faked.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OwnerHandoffTest {
    @Test
    fun ordinaryToModuleReleasesTheOrdinaryConnectionBeforeTheHostConnects() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        val id = rig.earphones.deviceId
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)

        val outcome = runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()

        assertIs<HandoffOutcome.Completed>(outcome)
        assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
        runCurrent()
        assertEquals(listOf("ordinary:open", "ordinary:close", "module:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        val owner = runtime.state.value.owner
        assertEquals(ControlWay.MODULE, owner.selected)
        assertEquals(OwnerReadiness.Ready, owner.readiness)
        assertEquals(HOST_PACKAGE, owner.holder?.packageName)
        assertEquals(ControlWay.MODULE, owner.holder?.way)
        val device = requireNotNull(runtime.state.value.device)
        assertEquals(rig.earphones.deviceId, device.deviceId)
        val left = assertIs<FieldReading.Known>(device.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldValue.Number(100), left.value)
        assertEquals(1, rig.headset.opens("ordinary"))
        assertEquals(1, rig.headset.opens("module"))
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
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
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
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.selected)
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(runtime.connect(rig.earphones.deviceId)).error.code)

        // Once the host can answer, the saved choice completes without another user action.
        rig.link.releaseHangs = false
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
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
        assertNotEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
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

        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
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
        val owner = runtime.state.value.owner
        assertEquals(ControlWay.MODULE, owner.selected)
        assertEquals(OwnerFailureStage.DISCOVERY, assertIs<OwnerReadiness.Unavailable>(owner.readiness).stage)
        assertNull(owner.holder)
        assertTrue(rig.appTransport.links.isEmpty())

        rig.link.reachable = true
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        assertEquals(ControlWay.MODULE, runtime.state.value.owner.selected)
        assertEquals(0, rig.headset.opens("ordinary"))
    }

    @Test
    fun disablingModuleControlDoesNotEnableOrdinaryControl() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()

        val outcome = runtime.disableModuleControl()
        runCurrent()

        assertIs<HandoffOutcome.Completed>(outcome)
        assertEquals(ControlWay.NONE, runtime.state.value.owner.selected)
        assertIs<OwnerReadiness.Idle>(runtime.state.value.owner.readiness)
        assertEquals(ControlWay.NONE, rig.store.record.selected)
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertEquals(0, rig.headset.opens("ordinary"))
        assertIs<HostStatus.Inactive>(rig.host.status.value)
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(runtime.connect(rig.earphones.deviceId)).error.code)
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
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.selected)
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

        val disabled = OwnerRig(this, initial = OwnerRecord(ControlWay.NONE))
        disabled.bootHost()
        val noOwner = disabled.newRuntime()
        runCurrent()
        assertIs<OwnerReadiness.Idle>(noOwner.state.value.owner.readiness)
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(noOwner.connectFromSystem(disabled.earphones.deviceId)).error.code)
        assertIs<HostStatus.Inactive>(disabled.host.status.value)

        val fresh = OwnerRig(this)
        fresh.bootHost()
        val ordinary = fresh.newRuntime()
        runCurrent()
        assertEquals(ControlWay.ORDINARY, ordinary.state.value.owner.selected)
        assertEquals(OwnerReadiness.Ready, ordinary.state.value.owner.readiness)
    }

    @Test
    fun aRestartThatCannotConfirmTheModuleReleaseKeepsOrdinaryControlBlocked() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease("r", OwnerGeneration("host-0", 4))))
        rig.bootHost()
        rig.link.reachable = false
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)

        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)
        assertTrue(rig.appTransport.links.isEmpty())

        // A later host incarnation that holds nothing proves the named earlier owner is gone.
        rig.link.reachable = true
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
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
            runtime.updateSettings(runtime.state.value.settings.copy(interfaceStyle = InterfaceStyle.MATERIAL3, forceBle = true))
            runtime.refreshBondedDevices()
            runCurrent()
            observer.await()
        }
        advanceUntilIdle()

        assertEquals(1, rig.headset.opens("module"))
        assertEquals(connectionBefore, runtime.state.value.connection)
        assertEquals(1, rig.link.connections.count { it.isOpen })
        assertEquals(ControlWay.MODULE, runtime.state.value.settings.controlWay)
        assertEquals(InterfaceStyle.MATERIAL3, runtime.state.value.settings.interfaceStyle)
    }

    @Test
    fun aPreferenceUpdateCannotMoveTheOwnerBackToOrdinary() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        val generation = (rig.host.status.value as HostStatus.Active).generation

        runtime.updateSettings(UserSettings(controlWay = ControlWay.ORDINARY))
        advanceUntilIdle()
        val forged = rig.host.execute(
            appIdentity, leaseOf(rig), generation, HostCommand.UpdateSettings(UserSettings(controlWay = ControlWay.NONE)),
        )

        assertEquals(HostCallResult.Operation(OperationResult.Success), forged)
        assertEquals(ControlWay.MODULE, runtime.state.value.owner.selected)
        assertIs<HostStatus.Active>(rig.host.status.value)
    }

    @Test
    fun nothingOwnsTheSessionBeforeFirstUnlockAndTheSavedWayDecidesAfterIt() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        val runtime = rig.newRuntime(userUnlocked = false)
        runCurrent()

        assertIs<OwnerReadiness.Idle>(runtime.state.value.owner.readiness)
        assertTrue(rig.link.connections.isEmpty())
        assertEquals(ControlErrorCode.NOT_READY, assertIs<OperationResult.Failure>(runtime.connectFromSystem(rig.earphones.deviceId)).error.code)
        assertIs<HandoffOutcome.Blocked>(runtime.selectControlWay(ControlWay.ORDINARY))
        assertEquals(ControlWay.MODULE, rig.store.record.selected)

        runtime.onUserUnlocked(UserSettings())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(ControlWay.MODULE, runtime.state.value.owner.selected)
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        assertEquals(0, rig.headset.opens("ordinary"))

        val ordinary = OwnerRig(this)
        val plain = ordinary.newRuntime(userUnlocked = false)
        runCurrent()
        assertTrue(ordinary.appTransport.links.isEmpty())
        plain.onUserUnlocked(UserSettings())
        runCurrent()
        assertEquals(OwnerReadiness.Ready, plain.state.value.owner.readiness)
    }

    @Test
    fun ordinaryBackgroundControlIsNotInForceUnderModuleControlAndKeepsItsSavedIntent() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.updateSettings(runtime.state.value.settings.copy(backgroundControlEnabled = true))
        runtime.updatePlatformState(
            PlatformState(
                PermissionState.GRANTED, TransportAvailability.Available, hostPlatform = HostPlatform.ANDROID,
                backgroundService = BackgroundServiceFacts(
                    wake = WakeFacts(WakeBand.ANDROID_8_11, CompanionAssociation.NOT_REQUIRED, PresenceObservation.NOT_REQUIRED),
                ),
            ),
        )
        assertEquals(BackgroundControlStatus.NotRunning, runtime.state.value.backgroundControl)
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_8_11), runtime.state.value.backgroundRecovery)

        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()

        assertEquals(BackgroundControlStatus.Disabled, runtime.state.value.backgroundControl)
        assertEquals(BackgroundRecovery.Disabled, runtime.state.value.backgroundRecovery)
        assertFalse(runtime.state.value.mayStartBackgroundService)
        assertTrue(runtime.state.value.settings.backgroundControlEnabled)

        runtime.selectControlWay(ControlWay.ORDINARY)
        runCurrent()
        assertEquals(BackgroundControlStatus.NotRunning, runtime.state.value.backgroundControl)
        assertEquals(BackgroundRecovery.Armed(WakeBand.ANDROID_8_11), runtime.state.value.backgroundRecovery)
    }

    @Test
    fun disablingWithAnUnprovenReleaseKeepsTheIntentIsNotCompletedAndNeverStartsOrdinary() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        val runtime = rig.newRuntime()
        runCurrent()
        runtime.selectControlWay(ControlWay.MODULE)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        rig.link.releaseHangs = true

        val blocked = assertIs<HandoffOutcome.Blocked>(runtime.disableModuleControl())

        assertEquals(OwnerFailureStage.RELEASE, blocked.stage)
        assertEquals(ControlWay.NONE, rig.store.record.selected)
        assertNotNull(rig.store.record.moduleRelease)
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)
        assertEquals(0, rig.headset.opens("ordinary"))

        // Once the host can answer, the saved disable completes by itself and still takes nothing over.
        rig.link.releaseHangs = false
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()

        assertIs<OwnerReadiness.Idle>(runtime.state.value.owner.readiness)
        assertEquals(ControlWay.NONE, runtime.state.value.owner.selected)
        assertNull(rig.store.record.moduleRelease)
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertTrue(rig.appTransport.links.isEmpty())
    }

    @Test
    fun aRestartedDisabledChoiceWithAnUnprovenReleaseKeepsAskingAndNeverStartsOrdinary() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.NONE, PendingModuleRelease("r", OwnerGeneration("host-0", 3))))
        rig.bootHost()
        rig.link.reachable = false
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)

        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)

        rig.link.reachable = true
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()

        assertIs<OwnerReadiness.Idle>(runtime.state.value.owner.readiness)
        assertNull(rig.store.record.moduleRelease)
        assertEquals(ControlWay.NONE, rig.store.record.selected)
        assertTrue(rig.appTransport.links.isEmpty())
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
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)

        rig.link.tamperReceipt = { it }
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
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
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
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
        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)
        assertNotNull(rig.store.record.moduleRelease)

        // The owner the host reported is then named explicitly, and its receipt is checked against that name.
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
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

        assertIs<OwnerReadiness.SwitchBlocked>(runtime.state.value.owner.readiness)
        assertTrue(rig.appTransport.links.isEmpty())
        assertNotNull(rig.store.record.moduleRelease)

        // A later host incarnation that holds nothing is proof the earlier named owner is gone.
        rig.link.releaseKillsHostProcess = false
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
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
            assertEquals(ControlWay.MODULE, runtime.state.value.owner.holder?.way)
            generations += (rig.host.status.value as HostStatus.Active).generation.sequence

            assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.ORDINARY))
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id))
            runCurrent()
            assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
            assertIs<HostStatus.Inactive>(rig.host.status.value)
        }

        val expected = buildList {
            add("module:open"); add("module:close"); add("ordinary:open")
            repeat(2) { add("ordinary:close"); add("module:open"); add("module:close"); add("ordinary:open") }
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
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.selected)

        rig.appTransport.closeFails = false
        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OperationResult.Success, runtime.connectFromSystem(rig.earphones.deviceId))
        runCurrent()
        assertEquals(listOf("ordinary:open", "ordinary:close", "module:open"), rig.headset.events)
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
            assertEquals(ControlWay.MODULE, runtime.state.value.owner.holder?.way)
            assertIs<FieldReading.Known>(device.fields[DeviceField.LEFT_BATTERY], profile.name)

            assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.ORDINARY))
            runCurrent()
            assertEquals(OperationResult.Success, runtime.connectFromSystem(id), profile.name)
            runCurrent()
            assertEquals(listOf("ordinary:open", "ordinary:close", "module:open", "module:close", "ordinary:open"), rig.headset.events, profile.name)
            assertEquals(0, rig.headset.overlappingOpens, profile.name)
            assertEquals(profile, runtime.state.value.device?.profile)
        }
    }

    private suspend fun leaseOf(rig: OwnerRig): String =
        (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}

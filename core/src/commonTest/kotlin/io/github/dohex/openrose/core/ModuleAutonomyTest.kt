package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Module control must run without the OpenRose application. These tests drive the module host from the
 * persisted choice and the unlock fact alone, and only afterwards bring up an application runtime to read
 * what the host did by itself; the earphones' transport and the IPC link are the only fakes.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModuleAutonomyTest {
    private val saved = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("earlier", null))

    @Test
    fun theHostControlsAnAlreadyConnectedHeadsetAfterFirstUnlockWithoutAnyApplicationProcess() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost(unlocked = false)
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()

        assertTrue(rig.headset.events.isEmpty())
        assertEquals(HostStatus.Inactive("host-1", null, "用户尚未解锁"), rig.host.status.value)

        rig.unlockHost()
        runCurrent()

        assertEquals(listOf("module:open"), rig.headset.events)
        assertIs<HostStatus.Active>(rig.host.status.value)
        assertTrue(rig.link.connections.isEmpty(), "nothing from the application was needed")

        // A runtime that appears later only reads: the real remote state and the earphones' own reply.
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        val left = assertIs<FieldReading.Known>(requireNotNull(runtime.state.value.device).fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldValue.Number(100), left.value)
        assertEquals(listOf("module:open"), rig.headset.events)
        assertEquals(0, rig.headset.opens("ordinary"))
    }

    @Test
    fun ordinaryOrDisabledChoicesAreNeverTakenOverByTheHostWhateverStartsOrReturns() = runTest {
        for (choice in listOf(ControlWay.ORDINARY, ControlWay.NONE)) {
            val rig = OwnerRig(this, initial = OwnerRecord(choice))
            rig.bootHost()
            rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
            runCurrent()
            // The module becoming available again, a hot reload and a killed host all start from the saved choice.
            rig.replaceHost(processDied = false)
            rig.bootHost()
            rig.replaceHost(processDied = true)
            rig.bootHost()
            rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
            rig.unlockHost()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertTrue(rig.headset.events.isEmpty(), "$choice: ${rig.headset.events}")
            assertIs<HostStatus.Inactive>(rig.host.status.value)
        }
    }

    @Test
    fun leavingTheApplicationNeverStopsTheEnabledHostWhetherItIsClosedReclaimedOrForceStopped() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        val process = rig.newAppProcess()
        val runtime = rig.newRuntime(process = process)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        val generation = (rig.host.status.value as HostStatus.Active).generation

        // The system reclaims the process, and a force stop kills it with its Binder links.
        process.cancel()
        rig.link.connections.filter { it.isOpen }.forEach { it.close() }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(HostStatus.Active(generation, (rig.host.status.value as HostStatus.Active).holder), rig.host.status.value)
        assertEquals(listOf("module:open"), rig.headset.events)

        // A later start of the application finds the same owner and does not reconnect or fall back.
        val again = rig.newRuntime(process = rig.newAppProcess())
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, again.state.value.owner.readiness)
        assertEquals(generation, (rig.host.status.value as HostStatus.Active).generation)
        assertEquals(listOf("module:open"), rig.headset.events)
        assertEquals(0, rig.appTransport.links.size)
    }

    @Test
    fun explicitlyDisablingReleasesTheConnectionAndKeepsTheHostFromComingBackAfterRestarts() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("module:open"), rig.headset.events)

        assertIs<HandoffOutcome.Completed>(runtime.disableModuleControl())
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)

        rig.replaceHost(processDied = true)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        assertEquals(0, rig.headset.opens("ordinary"))
        assertEquals(ControlWay.NONE, rig.store.record.selected)
    }

    @Test
    fun enteringModuleControlNeverOverlapsAnOrdinaryConnectionThatAudioHasAlreadyOpened() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("ordinary:open"), rig.headset.events)

        rig.appTransport.closeDelayMillis = 300
        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(listOf("ordinary:open", "ordinary:close", "module:open"), rig.headset.events)
    }

    @Test
    fun aKilledHostIsReplacedFromTheSavedChoiceAndEverythingOfTheOldOwnerIsInvalid() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        val old = rig.host
        val oldGeneration = (old.status.value as HostStatus.Active).generation
        val oldLease = (old.open(appIdentity) as HostOpenResult.Opened).lease.id
        val oldReference = SessionReference(
            requireNotNull(runtime.state.value.device).deviceId, requireNotNull(runtime.state.value.device).sessionId,
        )

        rig.replaceHost(processDied = true)
        runCurrent()
        // Until the new instance is told the saved choice nothing owns the session and nothing claims a release.
        assertEquals(OwnerReadiness.Unavailable(OwnerFailureStage.CONNECTION, "与模块宿主的连接已断开"), runtime.state.value.owner.readiness.takeIf { it is OwnerReadiness.Unavailable })
        assertEquals(0, rig.headset.opens("ordinary"))

        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()

        val newGeneration = (rig.host.status.value as HostStatus.Active).generation
        assertNotEquals(oldGeneration.incarnation, newGeneration.incarnation)
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        assertEquals(newGeneration, runtime.state.value.owner.holder?.generation)
        assertEquals(listOf("module:open", "module:close", "module:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(0, rig.headset.opens("ordinary"))
        // The old lease, generation and session mean nothing to the new owner.
        assertEquals(
            HostCallResult.Rejected(HostRejection.INVALID_LEASE, "控制宿主授权无效或已过期"),
            rig.host.execute(appIdentity, oldLease, newGeneration, HostCommand.StartScan),
        )
        val fresh = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        assertEquals(
            HostCallResult.Rejected(HostRejection.STALE_GENERATION, "所有者代际已过期"),
            rig.host.execute(appIdentity, fresh, oldGeneration, HostCommand.StartScan),
        )
        // Every read names the generation it was issued for, so a pre-restart reference cannot reach the new owner.
        assertIs<HostReadResult.Rejected>(rig.host.readSession(appIdentity, fresh, oldGeneration, oldReference))
    }

    @Test
    fun aHotReloadReleasesFirstThenTheReplacementRestoresTheSameSavedModulePath() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        val oldGeneration = (rig.host.status.value as HostStatus.Active).generation

        rig.replaceHost(processDied = false)
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()

        assertEquals(listOf("module:open", "module:close", "module:open"), rig.headset.events)
        assertEquals(OwnerReadiness.Ready, runtime.state.value.owner.readiness)
        assertEquals(ControlWay.MODULE, runtime.state.value.owner.selected)
        assertNotEquals(oldGeneration, runtime.state.value.owner.holder?.generation)
        assertEquals(0, rig.headset.opens("ordinary"))
        assertEquals(0, rig.headset.overlappingOpens)
    }

    @Test
    fun anUnavailableHostIsReportedHonestlyAndNeverReplacedByTheApplicationConnection() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        rig.replaceHost(processDied = true)
        rig.link.reachable = false
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        val owner = runtime.state.value.owner
        assertEquals(ControlWay.MODULE, owner.selected)
        assertIs<OwnerReadiness.Unavailable>(owner.readiness)
        assertNull(owner.holder)
        assertEquals(0, rig.headset.opens("ordinary"))
        assertEquals(ControlWay.MODULE, rig.store.record.selected)
    }

    @Test
    fun repeatedRecoveryAndConfigurationNeverCreateASecondOwner() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        repeat(3) {
            rig.bootHost()
            rig.unlockHost()
            rig.bootHost()
            runCurrent()
        }
        assertEquals(listOf("module:open"), rig.headset.events)
        assertEquals(1, (rig.host.status.value as HostStatus.Active).generation.sequence)
        repeat(2) {
            rig.replaceHost(processDied = it == 0)
            rig.bootHost()
            rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
            rig.bootHost()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
        }
        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(3, rig.headset.opens("module"))
        assertEquals(1, listOf(rig.headset.openOwner).count { it != null })
    }

    @Test
    fun aSwitchToOrdinaryInterruptedByTheHostDyingEndsWithExactlyOneOrdinaryOwnerAndNoModuleRevival() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.appTransport.audioDevice.emit(rig.earphones.deviceId)
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        rig.link.releaseKillsHostProcess = true

        runtime.selectControlWay(ControlWay.ORDINARY)
        runCurrent()
        rig.link.releaseKillsHostProcess = false
        rig.replaceHost(processDied = true)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()

        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(ControlWay.ORDINARY, runtime.state.value.owner.holder?.way)
        assertEquals(1, rig.headset.opens("module"))
        assertIs<HostStatus.Inactive>(rig.host.status.value)
    }

    @Test
    fun aReloadedHostRefusesNewLeasesAndItsOldLeasesStopMeaningAnything() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        val old = rig.host
        val generation = (old.status.value as HostStatus.Active).generation
        val lease = (old.open(appIdentity) as HostOpenResult.Opened).lease.id

        assertTrue(old.stop(), "the owner was confirmed released")
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)

        // A client that still holds the old endpoint cannot attach to an instance that will never own again.
        assertIs<HostOpenResult.Refused>(old.open(appIdentity))
        assertEquals(
            HostCallResult.Rejected(HostRejection.NO_OWNER, "当前没有控制所有者"),
            old.execute(appIdentity, lease, generation, HostCommand.StartScan),
        )
        old.configure(HostConfiguration(true, UserSettings(controlWay = ControlWay.MODULE)))
        assertIs<HostStatus.Inactive>(old.status.value)
        assertEquals(listOf("module:open", "module:close"), rig.headset.events)
    }
}

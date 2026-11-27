package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        val left = assertIs<FieldReading.Known>(requireNotNull(runtime.state.value.device).diagnostics.fields[DeviceField.LEFT_BATTERY])
        assertEquals(FieldValue.Number(100), left.value)
        assertEquals(listOf("module:open"), rig.headset.events)
        assertEquals(0, rig.headset.opens("app"))
    }

    @Test
    fun theAppChoiceIsNeverTakenOverByTheHostWhateverStartsOrReturns() = runTest {
        val rig = OwnerRig(this, initial = OwnerRecord(ControlWay.APP))
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        rig.replaceHost()
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.unlockHost()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(rig.headset.events.isEmpty(), "${rig.headset.events}")
        assertIs<HostStatus.Inactive>(rig.host.status.value)
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
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        val generation = (rig.host.status.value as HostStatus.Active).generation

        process.cancel()
        rig.link.connections.filter { it.isOpen }.forEach { it.close() }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(HostStatus.Active(generation, (rig.host.status.value as HostStatus.Active).holder), rig.host.status.value)
        assertEquals(listOf("module:open"), rig.headset.events)

        val again = rig.newRuntime(process = rig.newAppProcess())
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OwnerReadiness.Ready, again.integration.value.owner.readiness)
        assertEquals(generation, (rig.host.status.value as HostStatus.Active).generation)
        assertEquals(listOf("module:open"), rig.headset.events)
        assertEquals(0, rig.app.connections)
    }

    @Test
    fun enteringModuleControlNeverOverlapsAnAppConnectionThatAudioHasAlreadyOpened() = runTest {
        val rig = OwnerRig(this)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.app.audioConnected = true
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        rig.eventually("the app connection") { rig.headset.events == listOf("app:open") }
        assertEquals(listOf("app:open"), rig.headset.events)

        rig.app.closeDelayMillis = 300
        assertIs<HandoffOutcome.Completed>(runtime.selectControlWay(ControlWay.MODULE))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(listOf("app:open", "app:close", "module:open"), rig.headset.events)
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
            requireNotNull(runtime.state.value.device).deviceId,
            requireNotNull(runtime.state.value.device).sessionId,
        )

        rig.replaceHost()
        runCurrent()
        assertEquals(OwnerFailureStage.CONNECTION, assertIs<OwnerReadiness.Unavailable>(runtime.integration.value.owner.readiness).stage)
        assertEquals(0, rig.headset.opens("app"))

        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 2)
        runCurrent()

        val newGeneration = (rig.host.status.value as HostStatus.Active).generation
        assertNotEquals(oldGeneration.incarnation, newGeneration.incarnation)
        assertEquals(OwnerReadiness.Ready, runtime.integration.value.owner.readiness)
        assertEquals(newGeneration, runtime.integration.value.owner.holder?.generation)
        assertEquals(listOf("module:open", "module:close", "module:open"), rig.headset.events)
        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(0, rig.headset.opens("app"))
        assertEquals(
            HostRejection.INVALID_LEASE,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(appIdentity, oldLease, newGeneration, HostCommand.StartScan)).rejection,
        )
        val fresh = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        assertEquals(
            HostRejection.STALE_GENERATION,
            assertIs<HostCallResult.Rejected>(rig.host.control.execute(appIdentity, fresh, oldGeneration, HostCommand.StartScan)).rejection,
        )
        assertIs<HostReadResult.Rejected>(rig.host.control.readSession(appIdentity, fresh, oldGeneration, oldReference))
    }

    @Test
    fun anUnavailableHostIsReportedHonestlyAndNeverReplacedByTheApplicationConnection() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.app.audioConnected = true
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        rig.replaceHost()
        rig.link.reachable = false
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        val owner = runtime.integration.value.owner
        assertEquals(ControlWay.MODULE, owner.selected)
        assertIs<OwnerReadiness.Unavailable>(owner.readiness)
        assertNull(owner.holder)
        assertEquals(0, rig.headset.opens("app"))
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
            rig.replaceHost()
            rig.bootHost()
            rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
            rig.bootHost()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
        }
        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(3, rig.headset.opens("module"))
        assertEquals("module", rig.headset.openOwner)
    }

    @Test
    fun aSwitchToAppInterruptedByTheHostDyingEndsWithExactlyOneAppOwnerAndNoModuleRevival() = runTest {
        val rig = OwnerRig(this, initial = saved)
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.app.audioConnected = true
        val runtime = rig.newRuntime()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        rig.link.releaseKillsHostProcess = true

        runtime.selectControlWay(ControlWay.APP)
        runCurrent()
        rig.link.releaseKillsHostProcess = false
        rig.replaceHost()
        rig.bootHost()
        rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        runCurrent()
        advanceTimeBy(rig.clientTiming.retryMillis * 3)
        runCurrent()
        rig.eventually("the app connection") { rig.headset.openOwner == "app" }

        assertEquals(0, rig.headset.overlappingOpens)
        assertEquals(ControlWay.APP, runtime.integration.value.owner.holder?.way)
        assertEquals(1, rig.headset.opens("module"))
        assertIs<HostStatus.Inactive>(rig.host.status.value)
    }
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The fusion device center's additional settings entry, decided from the module host's own owner state and
 * the settings it persisted, so it needs neither the application process nor a mirrored copy of either.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FusionEntryTest {
    private val ble = "AA:BB:CC:DD:EE:01"
    private val classic = "11:22:33:44:55:66"

    private suspend fun TestScope.moduleRig(destination: FusionSettingsDestination, connected: Boolean = true): Pair<OwnerRig, String> {
        val rig = OwnerRig(this, earphones = rosePair(ble), initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        if (connected) rig.hostTransport.audioDevice.emit(rig.earphones.deviceId)
        rig.bootHost()
        // The user's persisted choice as the host reads it, not anything the application publishes at run time.
        rig.host.configure(HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE, fusionSettingsDestination = destination))))
        runCurrent()
        testScheduler.advanceUntilIdle()
        val lease = (rig.host.open(milinkIdentity) as HostOpenResult.Opened).lease.id
        return rig to lease
    }

    private fun decide(rig: OwnerRig, lease: String, address: String): FusionEntryDecision {
        val view = rig.host.view(milinkIdentity, lease)
        return FusionEntry.resolve(view?.state, view?.settings ?: IntegrationSettings(), address)
    }

    @Test
    fun theClassicAddressTheFusionCenterUsesOpensTheOwnedEarphones() = runTest {
        val (rig, lease) = moduleRig(FusionSettingsDestination.OPENROSE)

        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), decide(rig, lease, classic))
        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), decide(rig, lease, classic.lowercase()))
        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), decide(rig, lease, ble))
    }

    @Test
    fun theUsersSystemChoiceKeepsTheSystemSettingsPage() = runTest {
        val (rig, lease) = moduleRig(FusionSettingsDestination.SYSTEM)

        assertEquals(FusionEntryDecision.System, decide(rig, lease, classic))
    }

    @Test
    fun anotherHeadsetAndAnUnparsableAddressStayWithTheSystem() = runTest {
        val (rig, lease) = moduleRig(FusionSettingsDestination.OPENROSE)

        assertEquals(FusionEntryDecision.System, decide(rig, lease, "66:55:44:33:22:11"))
        assertEquals(FusionEntryDecision.System, decide(rig, lease, "not-an-address"))
        assertEquals(FusionEntryDecision.System, decide(rig, lease, ""))
    }

    @Test
    fun bondedRoseEarphonesThatAreNotConnectedYetStillOpenTheirOwnPage() = runTest {
        val (rig, lease) = moduleRig(FusionSettingsDestination.OPENROSE, connected = false)
        val app = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        val generation = (rig.host.status.value as HostStatus.Active).generation
        rig.host.execute(appIdentity, app, generation, HostCommand.RefreshBondedDevices)

        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), decide(rig, lease, classic))
    }

    @Test
    fun withoutAModuleOwnerTheSystemEntryIsLeftAlone() = runTest {
        val (rig, lease) = moduleRig(FusionSettingsDestination.OPENROSE)
        val app = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        val generation = (rig.host.status.value as HostStatus.Active).generation
        rig.host.release(appIdentity, app, SwitchRequest("x", ControlWay.MODULE, ControlWay.ORDINARY, generation))

        assertNull(rig.host.view(milinkIdentity, lease))
        assertIs<FusionEntryDecision.System>(decide(rig, lease, classic))
    }
}

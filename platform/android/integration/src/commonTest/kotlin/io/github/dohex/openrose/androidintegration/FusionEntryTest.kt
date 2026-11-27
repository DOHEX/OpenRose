package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FusionEntryTest {
    private val ble = "AA:BB:CC:DD:EE:01"
    private val classic = ble

    @Test
    fun aPairWhoseClassicAddressDiffersFromItsDeviceIdOpensTheOwnedEarphonesByEither() {
        val pair = "11:22:33:44:55:66"
        val state = ControlState(
            platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            bondedDevices = persistentListOf(DeviceCandidate(DeviceId(ble), "ROSE i5", null, ModelProfile.I5_ZT, "", pair)),
        )
        val settings = IntegrationSettings(fusionSettingsDestination = FusionSettingsDestination.OPENROSE)

        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), FusionEntry.resolve(state, settings, pair))
        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), FusionEntry.resolve(state, settings, pair.lowercase()))
        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), FusionEntry.resolve(state, settings, ble))
    }

    private suspend fun TestScope.moduleRig(destination: FusionSettingsDestination, connected: Boolean = true): Pair<OwnerRig, String> {
        val rig = OwnerRig(this, earphones = rosePair(ble), initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        if (connected) rig.hostHeadset.connectAudio(rig.earphones.deviceId)
        rig.bootHost()
        rig.host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE, fusionSettingsDestination = destination))) }
        runCurrent()
        testScheduler.advanceUntilIdle()
        val lease = (rig.host.open(milinkIdentity) as HostOpenResult.Opened).lease.id
        return rig to lease
    }

    private fun decide(rig: OwnerRig, lease: String, address: String): FusionEntryDecision {
        val view = rig.host.control.view(milinkIdentity, lease)
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
        rig.host.control.execute(appIdentity, app, generation, HostCommand.RefreshBondedDevices)

        assertEquals(FusionEntryDecision.OpenRose(DeviceId(ble)), decide(rig, lease, classic))
    }

    @Test
    fun withoutAModuleOwnerTheSystemEntryIsLeftAlone() = runTest {
        val (rig, lease) = moduleRig(FusionSettingsDestination.OPENROSE)
        val app = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        val generation = (rig.host.status.value as HostStatus.Active).generation
        rig.host.release(appIdentity, app, SwitchRequest("x", ControlWay.MODULE, ControlWay.APP, generation))

        assertIs<FusionEntryDecision.System>(decide(rig, lease, classic))
    }
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * On the target source device of a headset circulation the earphones are not connected yet, so there is no current
 * session; the module host still knows them from the system's bonded devices (ADR 0017).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BondedHeadsetsTest {
    private val ble = "AA:BB:CC:DD:EE:01"
    private val classic = "11:22:33:44:55:66"

    private suspend fun TestScope.moduleHost(): Pair<OwnerRig, String> {
        val rig = OwnerRig(this, earphones = rosePair(ble), initial = OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        rig.bootHost()
        runCurrent()
        testScheduler.advanceUntilIdle()
        val lease = (rig.host.open(milinkIdentity) as HostOpenResult.Opened).lease.id
        return rig to lease
    }

    @Test
    fun bondedRoseEarphonesWithoutASessionAreKnownByEveryAddress() = runTest {
        val (rig, lease) = moduleHost()
        val view = rig.host.control.view(milinkIdentity, lease)

        val state = assertNotNull(view).state
        assertNull(state.readySession)
        assertEquals(setOf(ble, classic), BondedHeadsets.addresses(state))
    }

    @Test
    fun bondedEarphonesWithoutAnIdentifiedModelAreNotRoseEarphones() {
        val state = ControlState(
            platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            bondedDevices = persistentListOf(
                DeviceCandidate(DeviceId("66:55:44:33:22:11"), "Other buds", null, null, "", "66:55:44:33:22:11"),
                DeviceCandidate(DeviceId("rose-opaque-id"), "ROSE i5", null, io.github.dohex.openrose.core.protocol.ModelProfile.I5_ZT, "", "aa:bb:cc:00:11:22"),
            ),
        )

        // Only Bluetooth addresses are published, normalized; an opaque control id is not one.
        assertEquals(setOf("AA:BB:CC:00:11:22"), BondedHeadsets.addresses(state))
    }

    @Test
    fun underOrdinaryControlTheModuleHostKnowsNoEarphones() = runTest {
        val (rig, lease) = moduleHost()
        val app = (rig.host.open(appIdentity) as HostOpenResult.Opened).lease.id
        val generation = (rig.host.status.value as HostStatus.Active).generation
        rig.host.release(appIdentity, app, SwitchRequest("x", ControlWay.MODULE, ControlWay.ORDINARY, generation))

        // No owner view: the host's currentSession reply names no earphones, and the system entries answer nothing.
        assertNull(rig.host.control.view(milinkIdentity, lease))
    }
}

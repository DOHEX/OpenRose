package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionCurrentHeadsetTest {
    private val first = candidate("rose-1", ModelProfile.I5_ZT)
    private val second = candidate("rose-2", ModelProfile.I7_RS_V2)
    private val third = candidate("rose-3", ModelProfile.I5_ZT)
    private val unrelated = candidate("speaker", profile = null)
    private val readyPlatform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available)

    @Test
    fun firstBondedHeadsetIsCurrentAndUnrelatedDevicesAreIgnored() = runTest {
        val transport = BondedOnlyTransport(listOf(unrelated))
        val runtime = LocalControlRuntime(readyPlatform, transport = transport, scope = backgroundScope)
        runCurrent()
        assertNull(runtime.state.value.currentHeadset)

        transport.bonded = listOf(unrelated, first, second)
        runtime.refreshBondedDevices()
        assertEquals(first, runtime.state.value.currentHeadset)
    }

    @Test
    fun selectingAnotherHeadsetPersistsWhileBondedHeadsetsStayTheSameAndClearsWhenTheyChange() = runTest {
        val transport = BondedOnlyTransport(listOf(first, second))
        val runtime = LocalControlRuntime(readyPlatform, transport = transport, scope = backgroundScope)
        runCurrent()
        assertEquals(first, runtime.state.value.currentHeadset)

        runtime.selectCurrentHeadset(second.deviceId)
        assertEquals(second, runtime.state.value.currentHeadset)

        transport.bonded = listOf(unrelated, first, second)
        runtime.refreshBondedDevices()
        assertEquals(second, runtime.state.value.currentHeadset)

        transport.bonded = listOf(first, second, third)
        runtime.refreshBondedDevices()
        assertEquals(first, runtime.state.value.currentHeadset)
    }

    @Test
    fun selectingAnUnknownOrNonRoseDeviceFallsBackToFirstBondedHeadset() = runTest {
        val transport = BondedOnlyTransport(listOf(unrelated, first, second))
        val runtime = LocalControlRuntime(readyPlatform, transport = transport, scope = backgroundScope)
        runCurrent()

        runtime.selectCurrentHeadset(second.deviceId)
        assertEquals(second, runtime.state.value.currentHeadset)

        runtime.selectCurrentHeadset(unrelated.deviceId)
        assertEquals(first, runtime.state.value.currentHeadset)

        runtime.selectCurrentHeadset(DeviceId("missing"))
        assertEquals(first, runtime.state.value.currentHeadset)
    }

    @Test
    fun connectionTargetWinsOverManualSelection() = runTest {
        val transport = BondedOnlyTransport(listOf(first, second, third))
        val runtime = LocalControlRuntime(
            readyPlatform.copy(transport = TransportAvailability.Disabled),
            transport = transport,
            scope = backgroundScope,
        )
        runCurrent()
        runtime.selectCurrentHeadset(second.deviceId)
        assertEquals(second, runtime.state.value.currentHeadset)

        runtime.connect(third.deviceId)
        assertEquals(third, runtime.state.value.currentHeadset)
    }

    @Test
    fun activeConnectionWithoutTargetWinsOverManualSelectionAndNeverSubstitutesAnotherHeadset() {
        val connected = ControlState(
            platform = readyPlatform,
            bondedDevices = persistentListOf(first, second),
            selectedHeadsetId = first.deviceId,
            connection = ConnectionState.Connected(second.deviceId, 1L, ModelProfile.I7_RS_V2),
        )
        assertEquals(second, connected.currentHeadset)
        assertNull(connected.copy(bondedDevices = persistentListOf(first)).currentHeadset)

        val connectingFromScan = ControlState(
            platform = readyPlatform,
            bondedDevices = persistentListOf(first),
            candidates = persistentListOf(third),
            selectedHeadsetId = first.deviceId,
            connection = ConnectionState.Connecting(third.deviceId, 2L, ConnectionPhase.CONNECTING),
        )
        assertEquals(third, connectingFromScan.currentHeadset)
    }

    private fun candidate(id: String, profile: ModelProfile?) = DeviceCandidate(
        deviceId = DeviceId(id),
        name = "Device $id",
        rssi = null,
        profile = profile,
        manufacturerDataHex = "",
    )

    private class BondedOnlyTransport(var bonded: List<DeviceCandidate>) : ControlTransport {
        override suspend fun bondedDevices(): List<DeviceCandidate> = bonded
        override fun scan(): Flow<Advertisement> = emptyFlow()
        override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = error("Unexpected connection")
    }
}

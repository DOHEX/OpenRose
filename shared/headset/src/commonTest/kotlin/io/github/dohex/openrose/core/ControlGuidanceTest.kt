package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ControlGuidanceTest {
    @Test
    fun connectionBlockReasonPrioritizesPermissionThenBluetoothDisabledThenTransportUnavailable() = runTest {
        val unavailable = TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable("dbus error"))
        val runtime = LocalControlRuntime(
            PlatformState(PermissionState.REQUIRED, unavailable),
            scope = backgroundScope,
        )
        assertEquals(ConnectionBlockReason.BluetoothPermissionRequired, runtime.state.value.connectionBlockReason)

        runtime.updatePlatformState(PlatformState(PermissionState.DENIED, TransportAvailability.Disabled))
        assertEquals(ConnectionBlockReason.BluetoothPermissionRequired, runtime.state.value.connectionBlockReason)

        runtime.updatePlatformState(PlatformState(PermissionState.GRANTED, TransportAvailability.Disabled))
        assertEquals(ConnectionBlockReason.BluetoothDisabled, runtime.state.value.connectionBlockReason)

        runtime.updatePlatformState(PlatformState(PermissionState.NOT_REQUIRED, unavailable))
        assertEquals(
            ConnectionBlockReason.TransportUnavailable(TransportUnavailableReason.BackendUnavailable("dbus error")),
            runtime.state.value.connectionBlockReason,
        )

        runtime.updatePlatformState(PlatformState(PermissionState.GRANTED, TransportAvailability.Available))
        assertNull(runtime.state.value.connectionBlockReason)
    }

    @Test
    fun reconnectGuidanceFollowsCaseLayoutAndUpdatesWithCurrentHeadset() = runTest {
        for (profile in ModelProfile.entries) {
            val expected = if (profile.batteryLayout == BatteryLayout.EARBUDS_AND_CASE) {
                ReconnectGuidance.OPEN_CASE_AUTO_CONNECT
            } else {
                ReconnectGuidance.WAIT_SYSTEM_BLUETOOTH
            }
            assertEquals(expected, profile.reconnectGuidance, profile.name)
        }
        assertEquals(
            ReconnectGuidance.WAIT_SYSTEM_BLUETOOTH,
            DeviceCandidate(DeviceId("unknown"), "Unknown", null, null, "").reconnectGuidance,
        )

        val withCase = DeviceCandidate(DeviceId("i5"), "EarFree i5", null, ModelProfile.I5_ZT, "")
        val withoutCase = DeviceCandidate(DeviceId("jasmine"), "Jasmine", null, ModelProfile.JASMINE_RS_V2, "")
        val transport = object : ControlTransport {
            override suspend fun bondedDevices(): List<DeviceCandidate> = listOf(withCase, withoutCase)
            override fun scan(): Flow<Advertisement> = emptyFlow()
            override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = error("Unexpected connection")
        }
        val runtime = LocalControlRuntime(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            transport = transport,
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals(ReconnectGuidance.OPEN_CASE_AUTO_CONNECT, runtime.state.value.reconnectGuidance)

        runtime.selectCurrentHeadset(withoutCase.deviceId)
        assertEquals(ReconnectGuidance.WAIT_SYSTEM_BLUETOOTH, runtime.state.value.reconnectGuidance)

        val emptyRuntime = LocalControlRuntime(
            PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            scope = backgroundScope,
        )
        assertNull(emptyRuntime.state.value.reconnectGuidance)
    }
}

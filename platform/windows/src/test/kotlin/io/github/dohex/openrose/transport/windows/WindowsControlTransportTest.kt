package io.github.dohex.openrose.transport.windows

import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsControlTransportTest {
    @Test
    fun pairedHeadsetStartsControlOnlyWithAnActiveAudioEndpoint() = runTest {
        val snapshots = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val transport = WindowsControlTransport(backgroundScope, snapshots) { error("unexpected native session") }
        runCurrent()
        snapshots.emit("available\t001122334455/45617246726565206935/0")
        runCurrent()
        assertEquals(TransportAvailability.Available, transport.availability.value)
        assertEquals("EarFree i5", transport.bondedDevices().single().name)
        assertNull(transport.audioConnectedDevice.first())
        snapshots.emit("available\t001122334455/45617246726565206935/1")
        runCurrent()
        assertEquals(DeviceId("00:11:22:33:44:55"), transport.audioConnectedDevice.first())
        snapshots.emit("disabled")
        runCurrent()
        assertEquals(TransportAvailability.Disabled, transport.availability.value)
        assertNull(transport.audioConnectedDevice.first())
        snapshots.emit("unavailable\tble_unsupported")
        runCurrent()
        assertEquals(TransportAvailability.Unavailable(TransportUnavailableReason.BleUnsupported), transport.availability.value)
        snapshots.emit("unavailable\tadapter_state_unknown")
        runCurrent()
        assertEquals(TransportAvailability.Unavailable(TransportUnavailableReason.AdapterStateUnknown), transport.availability.value)
        snapshots.emit("unavailable\trecovering")
        runCurrent()
        assertEquals(TransportAvailability.Unavailable(TransportUnavailableReason.Recovering), transport.availability.value)
        snapshots.emit("unavailable\tbackend_unavailable\thelper exited")
        runCurrent()
        assertEquals(
            TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable("helper exited")),
            transport.availability.value,
        )
        snapshots.emit("unavailable\tunrecognized_code")
        runCurrent()
        assertEquals(
            TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable("unrecognized_code")),
            transport.availability.value,
        )
        snapshots.emit("unavailable")
        runCurrent()
        assertEquals(
            TransportAvailability.Unavailable(TransportUnavailableReason.BackendUnavailable()),
            transport.availability.value,
        )
        transport.close()
    }
}

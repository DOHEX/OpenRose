package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionTiming
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.freedesktop.dbus.types.UInt16
import kotlin.test.*

class BluezControlTransportTest {
    @Test
    fun discoverySnapshotFailureKeepsItsReasonWhenCleanupAlsoFails() = runBlocking {
        val bus = FakeBluezBus().also {
            it.snapshot = baseObjects()
            it.stopDiscoveryFailure = IllegalStateException("cleanup failed")
            it.onStartDiscovery = { it.objectsFailure = IllegalStateException("advertisement read failed") }
        }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        val failure = assertFailsWith<IllegalStateException> { transport.scan().first() }
        assertEquals("advertisement read failed", failure.message)
        transport.close()
    }

    @Test
    fun deniedDiscoveryMarksTransportUnavailable() = runBlocking {
        val bus = FakeBluezBus().also {
            it.snapshot = baseObjects()
            it.discoveryFailure = org.freedesktop.dbus.errors.AccessDenied("denied")
        }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        assertFailsWith<org.freedesktop.dbus.errors.AccessDenied> { transport.scan().first() }
        assertIs<TransportAvailability.Unavailable>(transport.availability.value)
        assertEquals(0, bus.scanStops)
        transport.close()
    }

    @Test
    fun existingAudioStartsControlAfterAvailabilityAndReleasesOnRemoval() = runBlocking {
        val service = "$DEVICE_PATH/service0001"
        val writer = "$service/char0001"
        val notifier = "$service/char0002"
        val bus = FakeBluezBus().also {
            it.snapshot = baseObjects() + mapOf(
                DEVICE_PATH to mapOf(DEVICE to mapOf("Address" to "00:11:22:33:44:55", "Name" to "EarFree i5", "Paired" to true, "Connected" to true, "ServicesResolved" to true)),
                MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID, "State" to "idle")),
                service to mapOf(SERVICE to mapOf("UUID" to "011bf5da-0000-1000-8000-00805f9b34fb")),
                writer to mapOf(CHARACTERISTIC to mapOf("UUID" to "00007777-0000-1000-8000-00805f9b34fb", "Flags" to listOf("write"))),
                notifier to mapOf(CHARACTERISTIC to mapOf("UUID" to "00008888-0000-1000-8000-00805f9b34fb", "Flags" to listOf("notify"))),
            )
        }
        val released = CompletableDeferred<String>()
        bus.onStopNotify = { released.complete(it) }
        bus.onWrite = { bytes ->
            val command = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }.substring(10, 14)
            i5Replies[command]?.let { hex -> bus.events.emit(BluezSignal.Changed(notifier, CHARACTERISTIC, mapOf("Value" to hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = BluezControlTransport(scope, bus)
        transport.awaitInitialization()
        val initial = PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Unavailable("initializing"))
        val runtime = LocalControlRuntime(initial, transport = transport, scope = scope, timing = SessionTiming(i5WriteIntervalMillis = 0))
        withTimeout(5_000) { runtime.state.first { it.bondedDevices.isNotEmpty() } }
        withTimeout(5_000) { runtime.state.first { it.lastError?.code == ControlErrorCode.TRANSPORT_UNAVAILABLE } }
        val availability = scope.launch { transport.availability.collect { runtime.updatePlatformState(initial.copy(transport = it)) } }
        withTimeout(8_000) { runtime.state.first { it.connection is ConnectionState.Connected } }
        bus.snapshot = bus.snapshot - MEDIA_PATH
        bus.events.emit(BluezSignal.Removed(MEDIA_PATH, setOf(MEDIA_TRANSPORT)))
        withTimeout(5_000) { runtime.state.first { it.connection is ConnectionState.Disconnected } }
        assertEquals(notifier, withTimeout(5_000) { released.await() })
        availability.cancelAndJoin()
        transport.close()
        scope.cancel()
    }

    @Test
    fun scanConvertsManufacturerDataAndReleasesDiscovery() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = baseObjects() }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        assertEquals(TransportAvailability.Available, transport.availability.value)
        val result = withTimeout(5_000) { transport.scan().first() }
        assertEquals(DeviceId("00:11:22:33:44:55"), result.deviceId)
        assertContentEquals(byteArrayOf(1, 2, 3), result.manufacturerData[0x1234])
        assertEquals(1, bus.scans)
        assertEquals(1, bus.scanStops)
        transport.close()
    }

    @Test
    fun uuidAndGattConnectionAloneDoNotStartAudioButMediaTransportDoesAndRemovalEndsIt() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = baseObjects() }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        val events = Channel<DeviceId?>(Channel.UNLIMITED)
        val listener = launch { transport.audioConnectedDevice.collect { events.send(it) } }
        assertNull(withTimeout(5_000) { events.receive() })
        bus.snapshot = baseObjects() + (MEDIA_PATH to mapOf(MEDIA_TRANSPORT to mapOf("Device" to DEVICE_PATH, "UUID" to A2DP_UUID, "State" to "idle")))
        bus.events.emit(BluezSignal.Added(MEDIA_PATH, setOf(MEDIA_TRANSPORT)))
        assertEquals(DeviceId("00:11:22:33:44:55"), withTimeout(5_000) { events.receive() })
        bus.snapshot = baseObjects() - DEVICE_PATH
        bus.events.emit(BluezSignal.Removed(DEVICE_PATH, setOf(DEVICE)))
        assertNull(withTimeout(5_000) { events.receive() })
        listener.cancelAndJoin()
        transport.close()
    }

    @Test
    fun poweredOffAdapterIsUnavailableAndPairedQueryIsSafe() = runBlocking {
        val bus = FakeBluezBus().also { it.snapshot = mapOf(ADAPTER_PATH to mapOf(ADAPTER to mapOf("Powered" to false))) }
        val transport = BluezControlTransport(this, bus)
        transport.awaitInitialization()
        assertIs<TransportAvailability.Unavailable>(transport.availability.value)
        assertEquals(emptyList(), transport.bondedDevices())
        transport.close()
    }
}

private const val ADAPTER_PATH = "/org/bluez/hci0"
private const val DEVICE_PATH = "$ADAPTER_PATH/dev_00_11_22_33_44_55"
private const val MEDIA_PATH = "$DEVICE_PATH/fd0"
private const val A2DP_UUID = "0000110b-0000-1000-8000-00805f9b34fb"
private fun baseObjects(): BluezObjects = mapOf(
    ADAPTER_PATH to mapOf(ADAPTER to mapOf("Powered" to true)),
    DEVICE_PATH to mapOf(
        DEVICE to mapOf(
            "Address" to "00:11:22:33:44:55",
            "Name" to "ROSE EarFree i5",
            "Paired" to true,
            "Connected" to true,
            "UUIDs" to listOf(A2DP_UUID),
            "ManufacturerData" to mapOf(UInt16(0x1234) to byteArrayOf(1, 2, 3)),
        ),
    ),
)

private val i5Replies = mapOf(
    "0101" to "09ff00000101011100000164620000ffe2", "0602" to "09ff00000106020e000000010020",
    "0201" to "09ff00000102010b000118", "0603" to "09ff00000106030b00011e",
    "0801" to "09ff00000108010b00011e", "0d01" to "09ff0000010d010e00010205002d",
    "0401" to "09ff00000104011200060502040605030746", "0a01" to "09ff0000010a010b00001f",
    "0605" to "09ff00000106050e000101010126", "0c01" to "09ff0000010c010b000122",
    "0607" to "09ff00000106070b000223", "0604" to "09ff00000106040b00001e",
    "0e03" to "09ff0000010e030b000126",
)

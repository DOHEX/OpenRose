package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.core.transport.GattException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.dohex.openrose.core.transport.GattSubscription
import io.github.dohex.openrose.core.transport.GattEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlin.test.assertContentEquals
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class BluezGattDriverTest {
    @Test
    fun accessDeniedDuringConnectionIsReportedAsPermissionFailure() = runTest {
        val bus = FakeBluezBus()
        bus.connectFailure = org.bluez.Error.NotAuthorized("denied")
        val driver = BluezGattDriver(bus, "/org/bluez/hci0/dev_00_11_22_33_44_55")
        val error = assertFailsWith<GattException> { driver.connect() }
        assertEquals(GattFailureKind.PERMISSION, error.kind)
    }

    @Test
    fun nativeErrorTypesRemainDistinct() = runTest {
        val cases = listOf(
            org.bluez.Error.NotPermitted("denied") to GattFailureKind.PERMISSION,
            org.bluez.Error.NotSupported("unsupported") to GattFailureKind.UNSUPPORTED,
            org.bluez.Error.InvalidArguments("invalid") to GattFailureKind.INVALID_VALUE,
            org.bluez.Error.InProgress("busy") to GattFailureKind.TRANSIENT,
            org.freedesktop.dbus.errors.NoReply("timeout") to GattFailureKind.TRANSIENT,
            org.bluez.Error.NotConnected("gone") to GattFailureKind.DISCONNECTED,
            org.bluez.Error.Failed("unknown") to GattFailureKind.UNKNOWN,
        )
        for ((native, expected) in cases) {
            val bus = FakeBluezBus().also { it.connectFailure = native }
            val error = assertFailsWith<GattException> { BluezGattDriver(bus, devicePath).connect() }
            assertEquals(expected, error.kind)
        }
    }

    @Test
    fun adapterPowerOffAndDeviceRemovalEndTheConnection() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, devicePath)
        val off = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Changed("/org/bluez/hci0", ADAPTER, mapOf("Powered" to false)))
        assertEquals(GattFailureKind.DISCONNECTED, (off.await() as GattEvent.Disconnected).failure.kind)
        val removed = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Removed(devicePath, setOf(DEVICE)))
        assertEquals(GattFailureKind.DISCONNECTED, (removed.await() as GattEvent.Disconnected).failure.kind)
        val ownerLost = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Owner(false))
        assertEquals(GattFailureKind.DISCONNECTED, (ownerLost.await() as GattEvent.Disconnected).failure.kind)
    }

    @Test
    fun servicesChangedInvalidatesDiscoveredTable() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, devicePath)
        driver.discoverServices()
        val change = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Removed(servicePath, setOf(SERVICE)))
        assertEquals(GattEvent.ServicesChanged, change.await())
    }

    @Test
    fun classicConnectionRetriesForGattAndCloseOnlyReleasesNotificationLease() = runTest {
        val bus = FakeBluezBus()
        bus.snapshot = mapOf(devicePath to mapOf(DEVICE to mapOf("Connected" to true, "ServicesResolved" to false)))
        bus.onConnect = { if (it == 2) bus.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, devicePath)
        driver.connect()
        val characteristic = driver.discoverServices().single().characteristics.single()
        assertEquals(2, bus.connects)
        driver.setNotificationRouting(characteristic, true)
        driver.configureSubscription(characteristic, GattSubscription.NOTIFY)
        driver.close()
        assertEquals(listOf("start:$charPath", "stop:$charPath"), bus.notifications)
        assertEquals(2, bus.connects)
    }

    @Test
    fun notificationAndWritesKeepTheDiscoveredCharacteristicIdentity() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, devicePath)
        driver.connect()
        val characteristic = driver.discoverServices().single().characteristics.single()
        assertEquals(100, driver.requestMtu(512))
        driver.setNotificationRouting(characteristic, true)
        val result = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Changed(charPath, CHARACTERISTIC, mapOf("Value" to byteArrayOf(1, 2))))
        val event = result.await() as GattEvent.Notification
        assertSame(characteristic, event.characteristic)
        assertContentEquals(byteArrayOf(1, 2), event.value)
        driver.write(characteristic, byteArrayOf(3), true)
        assertEquals(charPath, bus.writes.single().first)
        assertContentEquals(byteArrayOf(3), bus.writes.single().second)
        assertEquals(true, bus.writes.single().third)
        driver.close()
    }
 }

private const val devicePath = "/org/bluez/hci0/dev_00_11_22_33_44_55"
private const val servicePath = "$devicePath/service0001"
private const val charPath = "$servicePath/char0002"
private fun gattObjects(): BluezObjects = mapOf(
    devicePath to mapOf(DEVICE to mapOf("Connected" to true, "ServicesResolved" to true)),
    servicePath to mapOf(SERVICE to mapOf("UUID" to "00001800-0000-1000-8000-00805f9b34fb")),
    charPath to mapOf(CHARACTERISTIC to mapOf("UUID" to "00002a00-0000-1000-8000-00805f9b34fb", "Flags" to listOf("write", "notify"), "MTU" to 100)),
)

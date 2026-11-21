package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.GattEvent
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.core.transport.GattSubscription
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
            val error = assertFailsWith<GattException> { BluezGattDriver(bus, DEVICE_PATH).connect() }
            assertEquals(expected, error.kind)
        }
    }

    @Test
    fun adapterPowerOffAndDeviceRemovalEndTheConnection() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        val off = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Changed("/org/bluez/hci0", ADAPTER, mapOf("Powered" to false)))
        assertEquals(GattFailureKind.DISCONNECTED, (off.await() as GattEvent.Disconnected).failure.kind)
        val removed = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Removed(DEVICE_PATH, setOf(DEVICE)))
        assertEquals(GattFailureKind.DISCONNECTED, (removed.await() as GattEvent.Disconnected).failure.kind)
        val ownerLost = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Owner(false))
        assertEquals(GattFailureKind.DISCONNECTED, (ownerLost.await() as GattEvent.Disconnected).failure.kind)
    }

    @Test
    fun busyAudioConnectStillAllowsTheResolvedGattServiceToBeUsed() = runTest {
        val bus = FakeBluezBus().apply {
            snapshot = gattObjects()
            connectFailure = org.bluez.Error.InProgress("In Progress")
        }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.connect()
        val characteristic = driver.discoverServices().single().characteristics.single()
        driver.configureSubscription(characteristic, GattSubscription.NOTIFY)
        assertEquals(listOf("start:$CHAR_PATH"), bus.notifications)
        driver.close()
    }

    @Test
    fun servicesChangedInvalidatesDiscoveredTable() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.discoverServices()
        val change = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Removed(SERVICE_PATH, setOf(SERVICE)))
        assertEquals(GattEvent.ServicesChanged, change.await())
    }

    @Test
    fun reconnectWithClassicAudioWaitsForTheLeBearerBeforeSubscribingToCachedServices() = runTest {
        val bus = FakeBluezBus()
        bus.snapshot = dualModeGattObjects(false)
        bus.onStartNotify = {
            if (bus.snapshot[DEVICE_PATH]?.get(LE_BEARER)?.get("Connected") != true) throw org.bluez.Error.NotConnected("Not connected")
        }
        bus.onConnect = {
            if (bus.connectBearers.last()) {
                launch {
                    delay(400)
                    bus.snapshot = dualModeGattObjects(true)
                }
            }
        }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.connect()
        val characteristic = driver.discoverServices().single().characteristics.single()
        driver.configureSubscription(characteristic, GattSubscription.NOTIFY)
        assertEquals(listOf("start:$CHAR_PATH"), bus.notifications)
        driver.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun leConnectionRejectedByTheAudioProfileRetriesWhenBluezCanAcceptIt() = runTest {
        val bus = FakeBluezBus()
        bus.snapshot = dualModeGattObjects(false)
        bus.onConnect = {
            if (testScheduler.currentTime < 200) throw org.bluez.Error.InProgress("In Progress")
            bus.snapshot = dualModeGattObjects(true)
        }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.connect()
        driver.discoverServices().single()
        assertTrue(testScheduler.currentTime < 1_000)
        assertTrue(bus.connectBearers.all { it })
        driver.close()
    }

    @Test
    fun busyLeRequestDoesNotConnectAgainWhenTheSystemCompletesIt() = runTest {
        val bus = FakeBluezBus()
        bus.snapshot = dualModeGattObjects(false)
        bus.onConnect = {
            launch {
                delay(50)
                bus.snapshot = dualModeGattObjects(true)
            }
            throw org.bluez.Error.InProgress("In Progress")
        }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.connect()
        driver.discoverServices().single()
        assertEquals(listOf(true), bus.connectBearers)
        driver.close()
    }

    @Test
    fun classicConnectionRetriesForGattAndCloseOnlyReleasesNotificationLease() = runTest {
        val bus = FakeBluezBus()
        bus.snapshot = mapOf(DEVICE_PATH to mapOf(DEVICE to mapOf("Connected" to true, "ServicesResolved" to false)))
        bus.onConnect = { if (it == 2) bus.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.connect()
        val characteristic = driver.discoverServices().single().characteristics.single()
        assertEquals(2, bus.connects)
        driver.setNotificationRouting(characteristic, true)
        driver.configureSubscription(characteristic, GattSubscription.NOTIFY)
        driver.close()
        assertEquals(listOf("start:$CHAR_PATH", "stop:$CHAR_PATH"), bus.notifications)
        assertEquals(2, bus.connects)
    }

    @Test
    fun firstReadWaitsForTheGattClientAfterBluezAcceptsANotificationLease() = runTest {
        val bus = FakeBluezBus().apply { snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        val characteristic = driver.discoverServices().single().characteristics.single()
        driver.configureSubscription(characteristic, GattSubscription.NOTIFY)
        var attached = false
        launch {
            delay(400)
            attached = true
        }
        val delivered = mutableListOf<Byte>()
        bus.onWrite = {
            if (!attached) throw org.bluez.Error.Failed("Not connected")
            delivered += it.single()
        }
        driver.write(characteristic, byteArrayOf(1), true)
        assertEquals(listOf<Byte>(1), delivered)
        // Once initialized, user writes keep the ordinary failure path.
        attached = false
        assertFailsWith<GattException> { driver.write(characteristic, byteArrayOf(2), true) }
        assertEquals(listOf<Byte>(1), delivered)
        driver.close()
    }

    @Test
    fun releaseRetriesWhileTheOwnerLivesAndCompletesAfterBluezRestarts() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        val characteristic = driver.discoverServices().single().characteristics.single()
        driver.configureSubscription(characteristic, GattSubscription.NOTIFY)
        bus.onStopNotify = { throw org.freedesktop.dbus.errors.NoReply("timeout") }
        assertFailsWith<GattException> { driver.close() }

        bus.owner = ":1.2"
        driver.close()
        assertEquals(listOf("start:$CHAR_PATH", "stop:$CHAR_PATH"), bus.notifications)
    }

    @Test
    fun notificationAndWritesKeepTheDiscoveredCharacteristicIdentity() = runTest {
        val bus = FakeBluezBus().also { it.snapshot = gattObjects() }
        val driver = BluezGattDriver(bus, DEVICE_PATH)
        driver.connect()
        val characteristic = driver.discoverServices().single().characteristics.single()
        assertEquals(100, driver.requestMtu(512))
        driver.setNotificationRouting(characteristic, true)
        val result = async(start = CoroutineStart.UNDISPATCHED) { driver.events.first() }
        bus.events.emit(BluezSignal.Changed(CHAR_PATH, CHARACTERISTIC, mapOf("Value" to byteArrayOf(1, 2))))
        val event = result.await() as GattEvent.Notification
        assertSame(characteristic, event.characteristic)
        assertContentEquals(byteArrayOf(1, 2), event.value)
        driver.write(characteristic, byteArrayOf(3), true)
        assertEquals(CHAR_PATH, bus.writes.single().first)
        assertContentEquals(byteArrayOf(3), bus.writes.single().second)
        assertEquals(true, bus.writes.single().third)
        driver.close()
    }
}

private const val DEVICE_PATH = "/org/bluez/hci0/dev_00_11_22_33_44_55"
private const val SERVICE_PATH = "$DEVICE_PATH/service0001"
private const val CHAR_PATH = "$SERVICE_PATH/char0002"
private fun dualModeGattObjects(leConnected: Boolean): BluezObjects {
    val objects = gattObjects()
    val bearers = mapOf(CLASSIC_BEARER to mapOf("Connected" to true), LE_BEARER to mapOf("Connected" to leConnected))
    return objects + (DEVICE_PATH to objects.getValue(DEVICE_PATH) + bearers)
}

private fun gattObjects(): BluezObjects = mapOf(
    DEVICE_PATH to mapOf(DEVICE to mapOf("Connected" to true, "ServicesResolved" to true)),
    SERVICE_PATH to mapOf(SERVICE to mapOf("UUID" to "00001800-0000-1000-8000-00805f9b34fb")),
    CHAR_PATH to mapOf(CHARACTERISTIC to mapOf("UUID" to "00002a00-0000-1000-8000-00805f9b34fb", "Flags" to listOf("write", "notify"), "MTU" to 100)),
)

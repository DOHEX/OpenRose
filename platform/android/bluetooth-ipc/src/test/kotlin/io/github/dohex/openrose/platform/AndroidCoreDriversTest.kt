package io.github.dohex.openrose.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.openrose_ffi.TransportDriverException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class AndroidCoreDriversTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theCoreMayAlwaysRecoverAnInitializationOnAndroid() {
        androidCoreTransport(app).use { transport -> assertTrue(transport.supportsInitializationRecovery()) }
    }

    @Test
    fun aGattLinkNeedsTheConnectPermissionAndAValidAddress() {
        androidCoreTransport(app).use { transport ->
            shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            assertFailsWith<TransportDriverException.PermissionDenied> { transport.openGatt(ADDRESS) }
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            assertFailsWith<TransportDriverException.InvalidArgument> { transport.openGatt("not an address") }
            transport.openGatt(ADDRESS)
        }
    }

    @Test
    fun anSppLinkReportsWhyItCannotStartWithTypedTransportErrors() {
        androidCoreTransport(app).use { transport ->
            shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            assertFailsWith<TransportDriverException.PermissionDenied> { transport.openSpp(ADDRESS, ADDRESS, SPP_UUID) }

            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_OFF)
            assertFailsWith<TransportDriverException.Unsupported> { transport.openSpp(ADDRESS, ADDRESS, SPP_UUID) }

            shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
            shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH, false)
            assertFailsWith<TransportDriverException.Unsupported> { transport.openSpp(ADDRESS, ADDRESS, SPP_UUID) }

            shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH, true)
            assertFailsWith<TransportDriverException.InvalidArgument> { transport.openSpp("not an address", "not an address", SPP_UUID) }
            transport.openSpp(ADDRESS, ADDRESS, SPP_UUID)
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
    }
}

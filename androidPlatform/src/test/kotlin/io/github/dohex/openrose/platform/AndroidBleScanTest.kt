package io.github.dohex.openrose.platform

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.location.LocationManager
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class AndroidBleScanTest {
    @Test
    fun locationServicesAreRequiredOnlyWhenStartingDiscovery() = runTest {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)

        assertEquals(TransportAvailability.Available, app.readPlatformState(permissionRequested = true).transport)
        val failure = assertFailsWith<IOException> { AndroidBleTransport(app).scan().first() }
        assertEquals("请在系统设置中开启定位服务，以便此 Android 版本扫描蓝牙设备", failure.message)
    }
}

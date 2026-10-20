package io.github.dohex.openrose

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.location.LocationManager
import android.provider.Settings
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability

internal fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

internal fun Context.readPlatformState(permissionRequested: Boolean): PlatformState {
    val granted = bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val permission = when {
        granted -> PermissionState.GRANTED
        permissionRequested -> PermissionState.DENIED
        else -> PermissionState.REQUIRED
    }
    val transport = when {
        !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ->
            TransportAvailability.Unavailable("此设备没有 BLE 硬件")
        !granted -> TransportAvailability.Unavailable("授予权限后可检测蓝牙并发现耳机")
        else -> {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> TransportAvailability.Unavailable("系统蓝牙适配器不可用")
                !adapter.isEnabled -> TransportAvailability.Disabled
                Build.VERSION.SDK_INT <= 30 && !locationServicesEnabled() ->
                    TransportAvailability.Unavailable("请在系统设置中开启定位服务，以便此 Android 版本扫描蓝牙设备")
                else -> TransportAvailability.Available
            }
        }
    }
    return PlatformState(
        permission = permission,
        transport = transport,
        module = ModuleAvailability.Unavailable("尚未收到兼容框架服务，普通客户端可独立运行"),
    )
}

private fun Context.locationServicesEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
    getSystemService(LocationManager::class.java)?.isLocationEnabled == true
} else {
    @Suppress("DEPRECATION")
    Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
        Settings.Secure.LOCATION_MODE_OFF
}

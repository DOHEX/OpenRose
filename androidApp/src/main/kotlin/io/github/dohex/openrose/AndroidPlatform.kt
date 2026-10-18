package io.github.dohex.openrose

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
        !granted -> TransportAvailability.Unavailable("授予权限后可检测蓝牙；控制传输尚未实现")
        else -> {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> TransportAvailability.Unavailable("系统蓝牙适配器不可用")
                !adapter.isEnabled -> TransportAvailability.Disabled
                else -> TransportAvailability.Unavailable("蓝牙已开启；Android 控制传输尚未实现")
            }
        }
    }
    return PlatformState(
        permission = permission,
        transport = transport,
        module = ModuleAvailability.Unavailable("系统模块尚未接入，普通客户端可独立运行"),
    )
}

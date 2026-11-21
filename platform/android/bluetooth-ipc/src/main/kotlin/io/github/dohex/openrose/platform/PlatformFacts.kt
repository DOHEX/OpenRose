package io.github.dohex.openrose.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability

/** The runtime permissions Bluetooth discovery and control need on this Android version. */
fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Permission used for a paired device's name, audio profiles and RFCOMM control. */
fun Context.hasBluetoothConnectPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        Manifest.permission.BLUETOOTH
    }
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** The control-relevant platform facts, read the same way in the application and in the module host. */
fun Context.readPlatformState(
    permissionRequested: Boolean,
    userUnlocked: Boolean = true,
): PlatformState {
    val granted = bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val runtimePermission = when {
        granted -> PermissionState.GRANTED
        permissionRequested -> PermissionState.DENIED
        else -> PermissionState.REQUIRED
    }
    // Before Android 12 the manifest's normal Bluetooth permissions cover an already-paired headset.
    // Fine location is a separate prerequisite for discovering a new one.
    val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val permission = if (legacy) PermissionState.NOT_REQUIRED else runtimePermission
    val transport = when {
        !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ->
            TransportAvailability.Unavailable("此设备没有 BLE 硬件")

        permission.missing -> TransportAvailability.Unavailable("授予权限后可检测蓝牙并发现耳机")

        else -> {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> TransportAvailability.Unavailable("系统蓝牙适配器不可用")
                !adapter.isEnabled -> TransportAvailability.Disabled
                else -> TransportAvailability.Available
            }
        }
    }
    return PlatformState(
        permission = permission,
        scanPermission = if (legacy) runtimePermission else PermissionState.NOT_REQUIRED,
        transport = transport,
        hostPlatform = HostPlatform.ANDROID,
        controlTransportApplicability = if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        ) {
            PlatformApplicability.APPLICABLE
        } else {
            PlatformApplicability.NOT_APPLICABLE
        },
        userUnlocked = userUnlocked,
    )
}

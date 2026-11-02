package io.github.dohex.openrose

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.location.LocationManager
import android.provider.Settings
import android.app.NotificationManager
import io.github.dohex.openrose.core.BackgroundServiceFact
import io.github.dohex.openrose.core.WakeFacts
import io.github.dohex.openrose.core.BackgroundServiceFacts
import io.github.dohex.openrose.core.RunningNotification
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability

internal fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

internal fun Context.readPlatformState(
    permissionRequested: Boolean,
    backgroundService: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
    wake: WakeFacts = WakeFacts(),
    userUnlocked: Boolean = true,
): PlatformState {
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
        hostPlatform = HostPlatform.ANDROID,
        systemIntegrationApplicability = classifyHyperOsBuild(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            incrementalVersion = Build.VERSION.INCREMENTAL,
        ),
        controlTransportApplicability = if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        ) PlatformApplicability.APPLICABLE else PlatformApplicability.NOT_APPLICABLE,
        backgroundService = BackgroundServiceFacts(backgroundService, runningNotification(), wake),
        userUnlocked = userUnlocked,
    )
}

/** The required service notification is independent of the focus notification setting and has its own channel. */
private fun Context.runningNotification(): RunningNotification {
    val manager = getSystemService(NotificationManager::class.java) ?: return RunningNotification.UNKNOWN
    val channelBlocked = manager.getNotificationChannel(ControlService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    return if (manager.areNotificationsEnabled() && !channelBlocked) RunningNotification.VISIBLE else RunningNotification.HIDDEN
}

private fun Context.locationServicesEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
    getSystemService(LocationManager::class.java)?.isLocationEnabled == true
} else {
    @Suppress("DEPRECATION")
    Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
        Settings.Secure.LOCATION_MODE_OFF
}

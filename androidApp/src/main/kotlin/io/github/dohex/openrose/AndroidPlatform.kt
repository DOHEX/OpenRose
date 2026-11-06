package io.github.dohex.openrose

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import io.github.dohex.openrose.androidintegration.BackgroundServiceFact
import io.github.dohex.openrose.androidintegration.BackgroundServiceFacts
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.RunningNotification
import io.github.dohex.openrose.androidintegration.WakeFacts
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability

internal fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

internal fun Context.readPlatformState(
    permissionRequested: Boolean,
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

/** The Android integration facts of this host; [IntegrationPlatformState.module] keeps its default until the framework reports in. */
internal fun Context.readIntegrationPlatform(
    backgroundService: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
    wake: WakeFacts = WakeFacts(),
): IntegrationPlatformState = IntegrationPlatformState(
    module = ModuleAvailability.Unavailable("尚未收到兼容框架服务，普通客户端可独立运行"),
    systemIntegrationApplicability = classifyHyperOsBuild(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        incrementalVersion = Build.VERSION.INCREMENTAL,
    ),
    backgroundService = BackgroundServiceFacts(backgroundService, runningNotification(), wake),
)

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

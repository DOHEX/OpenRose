package io.github.dohex.openrose.platform

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.androidintegration.StatusNotificationTarget

/**
 * What 状态通知点击目标 needs from the system, asked the same way where the target is chosen (the application
 * process) and where the notification is rendered (the Bluetooth process), so that the settings page and the
 * notification agree on whether a target can open.
 */
object StatusTargetLaunch {
    const val ROSELINK_PACKAGE = "cn.ikaile.ruoshui.client"
    const val SETTINGS_PACKAGE = "com.android.settings"
    const val SYSTEM_HEADSET_ACTIVITY = "com.android.settings.bluetooth.MiuiHeadsetActivity"
    const val SYSTEM_HEADSET_ACTION = "miui.bluetooth.action.HEADSET_SETTINGS"

    const val ROSELINK_MISSING = "RoseLink 未安装"
    const val SYSTEM_PAGE_UNAVAILABLE = "系统耳机详情页在此系统上不可用"
    const val SYSTEM_DEVICE_MISSING = "未取得耳机的系统蓝牙设备，无法打开系统耳机详情页"

    /** The sentence OpenRose shows after a notification tap fell back to it. */
    fun fallbackNotice(reason: String): String = "$reason，已打开 OpenRose"

    /** RoseLink's launcher intent, or null when RoseLink is not installed. */
    fun roseLinkIntent(packageManager: PackageManager): Intent? = packageManager.getLaunchIntentForPackage(ROSELINK_PACKAGE)

    /** HyperOS' headset page without the device extras; whether it resolves is the system's answer, not an assumption. */
    fun systemHeadsetIntent(): Intent = Intent(SYSTEM_HEADSET_ACTION).setClassName(SETTINGS_PACKAGE, SYSTEM_HEADSET_ACTIVITY)

    fun resolves(packageManager: PackageManager, intent: Intent): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(intent, 0) != null
    }

    /** Why each target that cannot open now cannot; a target that is absent can open. */
    fun unavailableTargets(packageManager: PackageManager): Map<StatusNotificationTarget, String> = buildMap {
        if (roseLinkIntent(packageManager) == null) put(StatusNotificationTarget.ROSELINK, ROSELINK_MISSING)
        if (!resolves(packageManager, systemHeadsetIntent())) put(StatusNotificationTarget.SYSTEM, SYSTEM_PAGE_UNAVAILABLE)
    }
}

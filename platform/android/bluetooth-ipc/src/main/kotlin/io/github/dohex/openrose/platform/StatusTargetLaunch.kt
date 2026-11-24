package io.github.dohex.openrose.platform

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.androidintegration.StatusNotificationTarget

object StatusTargetLaunch {
    const val ROSELINK_PACKAGE = "cn.ikaile.ruoshui.client"
    const val SETTINGS_PACKAGE = "com.android.settings"
    const val SYSTEM_HEADSET_ACTIVITY = "com.android.settings.bluetooth.MiuiHeadsetActivity"
    const val SYSTEM_HEADSET_ACTION = "miui.bluetooth.action.HEADSET_SETTINGS"

    const val ROSELINK_MISSING = "RoseLink 未安装"
    const val SYSTEM_PAGE_UNAVAILABLE = "系统耳机详情页在此系统上不可用"
    const val SYSTEM_DEVICE_MISSING = "未取得耳机的系统蓝牙设备，无法打开系统耳机详情页"

    fun fallbackNotice(reason: String): String = "$reason，已打开 OpenRose"

    fun roseLinkIntent(packageManager: PackageManager): Intent? = packageManager.getLaunchIntentForPackage(ROSELINK_PACKAGE)

    fun systemHeadsetIntent(): Intent = Intent(SYSTEM_HEADSET_ACTION).setClassName(SETTINGS_PACKAGE, SYSTEM_HEADSET_ACTIVITY)

    fun resolves(packageManager: PackageManager, intent: Intent): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(intent, 0) != null
    }

    fun unavailableTargets(packageManager: PackageManager): Map<StatusNotificationTarget, String> = buildMap {
        if (roseLinkIntent(packageManager) == null) put(StatusNotificationTarget.ROSELINK, ROSELINK_MISSING)
        if (!resolves(packageManager, systemHeadsetIntent())) put(StatusNotificationTarget.SYSTEM, SYSTEM_PAGE_UNAVAILABLE)
    }
}

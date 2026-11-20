package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile

internal val PlatformState.bluetoothPermissionRequired: Boolean
    get() = permission == PermissionState.REQUIRED || permission == PermissionState.DENIED

internal val PlatformState.connectionBlockReason: String?
    get() = if (bluetoothPermissionRequired) {
        "需要蓝牙权限才能连接这副耳机"
    } else {
        when (val availability = transport) {
            TransportAvailability.Available -> null
            TransportAvailability.Disabled -> "系统蓝牙已关闭"
            is TransportAvailability.Unavailable -> availability.reason
        }
    }

internal fun HostPlatform.firstConnectionHint(): String = when (this) {
    HostPlatform.ANDROID -> "打开耳机并靠近手机，必要时让耳机进入配对模式。"
    HostPlatform.WINDOWS, HostPlatform.LINUX -> "打开耳机并靠近电脑，必要时让耳机进入配对模式。"
    HostPlatform.UNKNOWN -> "打开耳机并靠近设备，必要时让耳机进入配对模式。"
}

internal fun ModelProfile?.reconnectHint(failed: Boolean): String = when {
    this?.batteryLayout == BatteryLayout.EARBUDS_AND_CASE ->
        if (failed) "连接未成功 · 请确认耳机已开盖并在附近" else "未连接 · 耳机开盖后将自动连接"

    failed -> "连接未成功 · 请确认耳机已开启并在附近"

    else -> "未连接 · 系统蓝牙连上耳机后将自动连接"
}

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ConnectionBlockReason
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.ReconnectGuidance
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableReason

fun TransportAvailability.Unavailable.displayText(
    hostPlatform: HostPlatform = HostPlatform.UNKNOWN,
): String = transportUnavailableText(reason, hostPlatform)

internal fun ConnectionBlockReason.displayText(hostPlatform: HostPlatform = HostPlatform.UNKNOWN): String = when (this) {
    ConnectionBlockReason.BluetoothPermissionRequired -> "需要蓝牙权限才能连接这副耳机"
    ConnectionBlockReason.BluetoothDisabled -> "系统蓝牙已关闭"
    is ConnectionBlockReason.TransportUnavailable -> transportUnavailableText(reason, hostPlatform)
}

private fun transportUnavailableText(
    reason: TransportUnavailableReason,
    hostPlatform: HostPlatform,
): String = when (reason) {
    is TransportUnavailableReason.UnsupportedPlatform -> unsupportedPlatformText(reason.platform)
    is TransportUnavailableReason.MissingDependency -> missingDependencyText(reason.detail)
    is TransportUnavailableReason.BackendUnavailable -> backendUnavailableText(reason.detail, hostPlatform)
    else -> fixedUnavailableText(reason, hostPlatform)
}

private fun fixedUnavailableText(
    reason: TransportUnavailableReason,
    hostPlatform: HostPlatform,
): String = when (reason) {
    TransportUnavailableReason.BleUnsupported ->
        hostLabel(hostPlatform, "此设备没有 BLE 硬件", windows = "Windows 蓝牙适配器不支持 BLE")

    TransportUnavailableReason.PermissionRequired -> "授予权限后可检测蓝牙并发现耳机"

    TransportUnavailableReason.NoAdapter ->
        hostLabel(hostPlatform, "未找到蓝牙适配器", android = "系统蓝牙适配器不可用")

    TransportUnavailableReason.AdapterPoweredOff -> "蓝牙适配器已关闭"

    TransportUnavailableReason.AdapterStateUnknown ->
        hostLabel(hostPlatform, "蓝牙适配器状态未知", windows = "Windows 蓝牙适配器状态未知")

    TransportUnavailableReason.AccessDenied -> "D-Bus 拒绝访问蓝牙"

    TransportUnavailableReason.ServiceUnavailable -> "BlueZ 服务不可用"

    TransportUnavailableReason.Initializing ->
        hostLabel(hostPlatform, "正在初始化蓝牙传输", linux = "正在初始化 BlueZ", windows = "正在读取 Windows 蓝牙状态")

    TransportUnavailableReason.Recovering ->
        hostLabel(hostPlatform, "正在恢复蓝牙状态", windows = "Windows 正在恢复蓝牙状态")

    TransportUnavailableReason.ShuttingDown -> "应用正在关闭"

    TransportUnavailableReason.Closed ->
        hostLabel(hostPlatform, "蓝牙传输已关闭", linux = "BlueZ 传输已关闭", windows = "Windows 传输已关闭")

    is TransportUnavailableReason.UnsupportedPlatform,
    is TransportUnavailableReason.MissingDependency,
    is TransportUnavailableReason.BackendUnavailable,
    -> error("handled by transportUnavailableText")
}

private fun unsupportedPlatformText(platform: String?): String = platform
    ?.takeIf(String::isNotBlank)
    ?.let { "$it 控制传输尚未实现" }
    ?: "平台传输尚未实现"

private fun missingDependencyText(detail: String?): String = detail
    ?.takeIf(String::isNotBlank)
    ?.let { "BlueZ 运行环境缺少依赖：$it" }
    ?: "BlueZ 运行环境缺少依赖"

private fun hostLabel(
    hostPlatform: HostPlatform,
    default: String,
    android: String = default,
    linux: String = default,
    windows: String = default,
): String = when (hostPlatform) {
    HostPlatform.ANDROID -> android
    HostPlatform.LINUX -> linux
    HostPlatform.WINDOWS -> windows
    HostPlatform.UNKNOWN -> default
}

private fun backendUnavailableText(detail: String?, hostPlatform: HostPlatform): String {
    val nonBlankDetail = detail?.takeIf(String::isNotBlank)
    return when (hostPlatform) {
        HostPlatform.LINUX -> nonBlankDetail?.let { "BlueZ 不可用：$it" } ?: "BlueZ 不可用"
        HostPlatform.WINDOWS -> nonBlankDetail ?: "Windows 蓝牙不可用"
        HostPlatform.ANDROID, HostPlatform.UNKNOWN -> nonBlankDetail ?: "蓝牙传输不可用"
    }
}

internal fun HostPlatform.firstConnectionHint(): String = when (this) {
    HostPlatform.ANDROID -> "打开耳机并靠近手机，必要时让耳机进入配对模式。"
    HostPlatform.WINDOWS, HostPlatform.LINUX -> "打开耳机并靠近电脑，必要时让耳机进入配对模式。"
    HostPlatform.UNKNOWN -> "打开耳机并靠近设备，必要时让耳机进入配对模式。"
}

internal fun ReconnectGuidance.reconnectHint(failed: Boolean, includeStatus: Boolean = true): String {
    if (failed) {
        return when (this) {
            ReconnectGuidance.OPEN_CASE_AUTO_CONNECT -> "请确认耳机已开盖并在附近"
            ReconnectGuidance.WAIT_SYSTEM_BLUETOOTH -> "请确认耳机已开启并在附近"
        }
    }
    val guidance = when (this) {
        ReconnectGuidance.OPEN_CASE_AUTO_CONNECT -> "耳机开盖后将自动连接"
        ReconnectGuidance.WAIT_SYSTEM_BLUETOOTH -> "系统蓝牙连上耳机后将自动连接"
    }
    return if (includeStatus) "未连接 · $guidance" else guidance
}

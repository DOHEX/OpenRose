package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ConnectionBlockReason
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.ReconnectGuidance
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableCode

fun TransportAvailability.Unavailable.displayText(
    hostPlatform: HostPlatform = HostPlatform.UNKNOWN,
): String = transportUnavailableText(code, detail, hostPlatform)

internal fun ConnectionBlockReason.displayText(hostPlatform: HostPlatform = HostPlatform.UNKNOWN): String = when (this) {
    ConnectionBlockReason.BluetoothPermissionRequired -> "需要蓝牙权限才能连接这副耳机"
    ConnectionBlockReason.BluetoothDisabled -> "系统蓝牙已关闭"
    is ConnectionBlockReason.TransportUnavailable -> transportUnavailableText(code, detail, hostPlatform)
}

private val unavailableFormatters: Map<TransportUnavailableCode, (String?, HostPlatform) -> String> = mapOf(
    TransportUnavailableCode.UNSUPPORTED_PLATFORM to { detail, _ -> detail?.let { "$it 控制传输尚未实现" } ?: "平台传输尚未实现" },
    TransportUnavailableCode.BLE_UNSUPPORTED to { _, host -> hostLabel(host, "此设备没有 BLE 硬件", windows = "Windows 蓝牙适配器不支持 BLE") },
    TransportUnavailableCode.PERMISSION_REQUIRED to { _, _ -> "授予权限后可检测蓝牙并发现耳机" },
    TransportUnavailableCode.NO_ADAPTER to { _, host -> hostLabel(host, "未找到蓝牙适配器", android = "系统蓝牙适配器不可用") },
    TransportUnavailableCode.ADAPTER_POWERED_OFF to { _, _ -> "蓝牙适配器已关闭" },
    TransportUnavailableCode.ADAPTER_STATE_UNKNOWN to { _, host -> hostLabel(host, "蓝牙适配器状态未知", windows = "Windows 蓝牙适配器状态未知") },
    TransportUnavailableCode.ACCESS_DENIED to { _, _ -> "D-Bus 拒绝访问蓝牙" },
    TransportUnavailableCode.SERVICE_UNAVAILABLE to { _, _ -> "BlueZ 服务不可用" },
    TransportUnavailableCode.MISSING_DEPENDENCY to { detail, _ -> detail?.let { "BlueZ 运行环境缺少依赖：$it" } ?: "BlueZ 运行环境缺少依赖" },
    TransportUnavailableCode.INITIALIZING to { _, host ->
        hostLabel(host, "正在初始化蓝牙传输", linux = "正在初始化 BlueZ", windows = "正在读取 Windows 蓝牙状态")
    },
    TransportUnavailableCode.RECOVERING to { _, host -> hostLabel(host, "正在恢复蓝牙状态", windows = "Windows 正在恢复蓝牙状态") },
    TransportUnavailableCode.SHUTTING_DOWN to { _, _ -> "应用正在关闭" },
    TransportUnavailableCode.CLOSED to { _, host ->
        hostLabel(host, "蓝牙传输已关闭", linux = "BlueZ 传输已关闭", windows = "Windows 传输已关闭")
    },
    TransportUnavailableCode.BACKEND_UNAVAILABLE to ::backendUnavailableText,
)

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

private fun transportUnavailableText(
    code: TransportUnavailableCode,
    detail: String?,
    hostPlatform: HostPlatform,
): String {
    val nonBlankDetail = detail?.takeIf(String::isNotBlank)
    return unavailableFormatters.getValue(code)(nonBlankDetail, hostPlatform)
}

private fun backendUnavailableText(detail: String?, hostPlatform: HostPlatform): String = when (hostPlatform) {
    HostPlatform.LINUX -> detail?.let { "BlueZ 不可用：$it" } ?: "BlueZ 不可用"
    HostPlatform.WINDOWS -> detail?.let { "Windows 蓝牙状态不可用：$it" } ?: "Windows 蓝牙不可用"
    HostPlatform.ANDROID, HostPlatform.UNKNOWN -> detail ?: "蓝牙传输不可用"
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

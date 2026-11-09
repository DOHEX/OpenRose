package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

/** How far the fabricated control connection has come; the app's own connect and disconnect move it too. */
enum class SandboxLink(val label: String) {
    FIRST_VISIT("首访无配对"),
    PAIRED("未连接看板"),
    CONNECTING("正在连接"),
    INITIALIZING("初始化中"),
    READY("已就绪"),
}

/** A platform fact that keeps OpenRose from controlling earphones. */
enum class SandboxPlatformIssue(val label: String) {
    NONE("无"),
    PERMISSION_DENIED("蓝牙权限被拒"),
    BLUETOOTH_OFF("蓝牙已关闭"),
    TRANSPORT_UNAVAILABLE("无控制传输"),
}

/** Earbud battery; the case reads a little lower, a single unit reads the earbud value. */
enum class SandboxBattery(val label: String, val percent: Int?) {
    FULL("满电", 100),
    HALF("半电", 55),
    LOW("低电", 12),
    NOT_PROVIDED("未提供", null),
}

/** How every simulated setting write ends. */
enum class SandboxWriteOutcome(val label: String) {
    CONFIRMED("回读确认"),
    TIMED_OUT("回读超时"),
    NOT_APPLIED("设备未应用"),
}

enum class SandboxModule(val label: String) {
    AVAILABLE("模块可用"),
    NOT_ENABLED("模块未启用"),
    FRAMEWORK_MISSING("框架缺失"),
    UNRESPONSIVE("宿主无响应"),
}

enum class SandboxOwner(val label: String) {
    READY("所有者就绪"),
    STARTING("正在启动"),
    UNAVAILABLE("不可用"),
    SWITCH_BLOCKED("释放未确认"),
}

enum class SandboxBackgroundService(val label: String) {
    NOT_RUNNING("未运行"),
    RUNNING("运行中"),
    START_REJECTED("启动被拒"),
}

enum class SandboxTray(val label: String) {
    CHECKING("正在检查"),
    AVAILABLE("可用"),
    UNAVAILABLE("无托盘宿主"),
}

enum class SandboxLoginLaunch(val label: String) {
    READING("正在读取"),
    OFF("已关闭"),
    ON("已开启"),
    DISABLED_BY_USER("系统中被关闭"),
    UNSUPPORTED("仅安装版"),
}

/** Every fact the sandbox fabricates; the preview is a pure function of it plus the writes made since. */
data class SandboxScenario(
    val host: HostPlatform = HostPlatform.ANDROID,
    val profile: ModelProfile = ModelProfile.I7_RS_V2,
    /** Dotted firmware parts, such as `2.1.3`; blank means the firmware has not been read. */
    val firmware: String = "2.1.3",
    val link: SandboxLink = SandboxLink.READY,
    val battery: SandboxBattery = SandboxBattery.FULL,
    val issue: SandboxPlatformIssue = SandboxPlatformIssue.NONE,
    val writeOutcome: SandboxWriteOutcome = SandboxWriteOutcome.CONFIRMED,
    val hyperOs: Boolean = true,
    val module: SandboxModule = SandboxModule.NOT_ENABLED,
    val owner: SandboxOwner = SandboxOwner.READY,
    val backgroundService: SandboxBackgroundService = SandboxBackgroundService.NOT_RUNNING,
    val tray: SandboxTray = SandboxTray.AVAILABLE,
    val loginLaunch: SandboxLoginLaunch = SandboxLoginLaunch.OFF,
) {
    /** Null for blank or malformed text, which the session treats as unread firmware; shown as the codecs show it. */
    val firmwareValue: FieldValue.Firmware?
        get() {
            val parts = firmware.trim().split('.').map { it.toIntOrNull() ?: return null }
            if (parts.any { it !in 0..255 }) return null
            return FieldValue.Firmware(parts, parts.joinToString(""))
        }

    /** A platform that cannot control earphones holds no control connection, as the runtime would drop it. */
    fun normalized(): SandboxScenario = if (issue != SandboxPlatformIssue.NONE && link > SandboxLink.PAIRED) {
        copy(link = SandboxLink.PAIRED)
    } else {
        this
    }

    /** Windows has no control transport yet, so switching to it starts where the real Windows app starts. */
    fun onHost(next: HostPlatform): SandboxScenario = copy(
        host = next,
        issue = if (next == HostPlatform.WINDOWS) SandboxPlatformIssue.TRANSPORT_UNAVAILABLE else SandboxPlatformIssue.NONE,
    )

    val desktop: Boolean get() = host == HostPlatform.LINUX || host == HostPlatform.WINDOWS

    /** The name the desktop app reads from `os.name`. */
    val platformName: String
        get() = when (host) {
            HostPlatform.ANDROID -> "Android"
            HostPlatform.WINDOWS -> "Windows 11"
            HostPlatform.LINUX -> "Linux"
            HostPlatform.UNKNOWN -> "Unknown"
        }
}

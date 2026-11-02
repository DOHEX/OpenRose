package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.BackgroundControlBlock
import io.github.dohex.openrose.core.BackgroundControlStatus
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.UserSettings

/** Ordinary background control applies wherever Android hosts the control runtime. */
internal fun ControlState.backgroundControlApplicable(): Boolean =
    platform.hostPlatform == HostPlatform.ANDROID &&
        platform.controlTransportApplicability == PlatformApplicability.APPLICABLE &&
        backgroundControl != BackgroundControlStatus.Unsupported

/**
 * The switch only stores the user's intent. The status rows below it report what the platform
 * actually did, so a saved preference is never shown as background control being ready.
 */
@Composable
internal fun BackgroundControlGroup(
    state: ControlState,
    onChange: (UserSettings) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val settings = state.settings
    AppGroup(
        title = "后台控制",
        footnote = "无需 root 或模块。系统不允许后台运行时，应用无法自行绕过；" +
            "部分系统还需在系统设置中允许 OpenRose 自启动并取消电量限制，这些设置无法被检测。" +
            "在系统中强制停止 OpenRose 会结束后台控制。",
    ) {
        AppSwitchRow(
            title = "后台控制",
            summary = "关闭界面或移除任务后，保持已连接耳机的控制，并显示运行通知",
            checked = settings.backgroundControlEnabled,
            onChange = { onChange(settings.copy(backgroundControlEnabled = it)) },
        )
        when (val status = state.backgroundControl) {
            BackgroundControlStatus.Unsupported, BackgroundControlStatus.Disabled -> Unit
            BackgroundControlStatus.NotRunning ->
                ToneInfoRow("后台状态", "已启用，尚未运行；打开 OpenRose 后启动", StatusTone.CAUTION)
            is BackgroundControlStatus.Running -> {
                ToneInfoRow("后台状态", "运行中", StatusTone.GOOD)
                if (!status.notificationVisible) {
                    ToneInfoRow("运行通知", "系统已隐藏，后台控制仍在运行", StatusTone.CAUTION)
                    AppButton(
                        text = "允许通知",
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            is BackgroundControlStatus.Blocked -> {
                val (message, tone) = when (status.reason) {
                    BackgroundControlBlock.BLUETOOTH_PERMISSION ->
                        "缺少蓝牙权限，后台控制无法运行" to StatusTone.ERROR
                    BackgroundControlBlock.BACKGROUND_START_RESTRICTED ->
                        "系统限制了从后台启动，请打开 OpenRose 后重试" to StatusTone.CAUTION
                    BackgroundControlBlock.SERVICE_START_REFUSED ->
                        "系统拒绝了前台服务启动，后台控制未运行" to StatusTone.ERROR
                }
                ToneInfoRow("后台状态", message, tone)
                if (status.reason == BackgroundControlBlock.BLUETOOTH_PERMISSION) {
                    AppButton(
                        text = "授予蓝牙权限",
                        onClick = onRequestPermission,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

package io.github.dohex.openrose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.ui.AppArrowRow
import io.github.dohex.openrose.ui.AppGroup
import io.github.dohex.openrose.ui.AppInfoRow
import io.github.dohex.openrose.ui.AppSwitchRow
import io.github.dohex.openrose.ui.PlatformSettingsPosition
import io.github.dohex.openrose.ui.PlatformUi
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Desktop sections: the tray on the status page, 后台控制 and 登录时启动 in settings. */
internal class DesktopPlatformUi(
    override val platformName: String,
    private val desktopSettings: StateFlow<DesktopSettings>,
    private val onDesktopSettingsChange: (DesktopSettings) -> Unit,
    private val tray: StateFlow<TrayAvailability>,
    private val loginLaunch: LoginLaunch,
) : PlatformUi {
    override val openBluetoothSettings: (() -> Unit)? = bluetoothSettingsCommand(platformName)?.let { args ->
        {
            runCatching {
                ProcessBuilder(args)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            Unit
        }
    }

    @Composable
    override fun StatusSections(state: ControlState) {
        val availability by tray.collectAsState()
        AppGroup(title = "托盘") {
            AppInfoRow(
                "托盘图标",
                when (val current = availability) {
                    TrayAvailability.Checking -> "正在检查"
                    TrayAvailability.Available -> "可用"
                    is TrayAvailability.Unavailable -> "不可用 · ${current.reason}"
                },
            )
        }
    }

    @Composable
    override fun SettingsSections(state: ControlState, position: PlatformSettingsPosition) {
        if (position != PlatformSettingsPosition.AFTER_CONNECTION) return
        val settings by desktopSettings.collectAsState()
        val availability by tray.collectAsState()
        val login by loginLaunch.state.collectAsState()
        AppGroup(title = "后台控制") {
            AppSwitchRow(
                title = "后台控制",
                summary = when (val current = availability) {
                    is TrayAvailability.Unavailable -> "托盘不可用，关闭窗口将退出：${current.reason}"
                    else -> "关闭窗口后保留在托盘，耳机控制不中断；从托盘退出才结束控制连接"
                },
                checked = settings.backgroundControl,
                onChange = { onDesktopSettingsChange(settings.copy(backgroundControl = it)) },
            )
            AppSwitchRow(
                title = "登录时启动",
                summary = when (val current = login) {
                    LoginLaunchState.Reading -> "正在读取"
                    is LoginLaunchState.Ready -> "开启后台控制且托盘可用时，登录后只显示托盘图标"
                    LoginLaunchState.DisabledByUser -> "已在系统设置中关闭，需在系统设置中重新开启"
                    is LoginLaunchState.Unsupported -> current.reason
                },
                checked = (login as? LoginLaunchState.Ready)?.enabled == true,
                enabled = login is LoginLaunchState.Ready,
                onChange = loginLaunch::set,
            )
            if (login == LoginLaunchState.DisabledByUser) {
                AppArrowRow("打开系统启动项设置", onClick = loginLaunch::openSystemSettings)
            }
        }
    }
}

private fun bluetoothSettingsCommand(platform: String): List<String>? {
    if (!platform.contains("linux", ignoreCase = true)) return null
    val directories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
    return listOf(
        listOf("gnome-control-center", "bluetooth"),
        listOf("systemsettings", "kcm_bluetooth"),
        listOf("blueman-manager"),
    ).firstNotNullOfOrNull { candidate ->
        directories.firstNotNullOfOrNull { directory ->
            val executable = File(directory.ifEmpty { "." }, candidate.first())
            if (executable.isFile && executable.canExecute()) listOf(executable.path) + candidate.drop(1) else null
        }
    }
}

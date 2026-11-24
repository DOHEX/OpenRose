package io.github.dohex.openrose.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.ui.AppArrowRow
import io.github.dohex.openrose.ui.AppFactRow
import io.github.dohex.openrose.ui.AppGroup
import io.github.dohex.openrose.ui.AppStatusSection
import io.github.dohex.openrose.ui.AppSwitchRow
import io.github.dohex.openrose.ui.CirculationSettings
import io.github.dohex.openrose.ui.DesktopSoundActions
import io.github.dohex.openrose.ui.PlatformSettingsPosition
import io.github.dohex.openrose.ui.PlatformUi
import io.github.dohex.openrose.ui.RoseIcon
import io.github.dohex.openrose.ui.StatusTone
import io.github.dohex.openrose.ui.TrayPanelSettingsPage
import kotlinx.coroutines.flow.StateFlow

class DesktopPlatformUi(
    override val platformName: String,
    private val desktopSettings: StateFlow<DesktopSettings>,
    private val onDesktopSettingsChange: (DesktopSettings) -> Unit,
    private val tray: StateFlow<TrayAvailability>,
    private val loginLaunch: LoginLaunchControl,
    override val openBluetoothSettings: (() -> Unit)?,
    private val circulation: CirculationClient? = null,
    private val showQr: (@Composable (String) -> Unit)? = null,
    override val desktopSound: DesktopSoundActions? = null,
) : PlatformUi {
    override val headsetCirculation: CirculationClient?
        @Composable get() = circulation

    override val circulationPairingContent: (@Composable () -> Unit)?
        @Composable get() = circulation?.let { client -> { CirculationSettings(client, showQr = showQr) } }

    override val trayPanelSettingsSummary: String
        @Composable get() {
            val settings by desktopSettings.collectAsState()
            return settings.trayPanel.summaryText()
        }

    override val trayPanelSettingsContent: @Composable () -> Unit
        @Composable get() = {
            val settings by desktopSettings.collectAsState()
            TrayPanelSettingsPage(
                settings = settings.trayPanel,
                onChange = { onDesktopSettingsChange(settings.copy(trayPanel = it)) },
            )
        }

    @Composable
    override fun StatusSections(state: ControlState) {
        val availability by tray.collectAsState()
        val (value, tone) = when (val current = availability) {
            TrayAvailability.Checking -> "正在检查" to StatusTone.NEUTRAL
            TrayAvailability.Available -> "可用" to StatusTone.GOOD
            is TrayAvailability.Unavailable -> "不可用 · ${current.reason}" to StatusTone.CAUTION
        }
        AppStatusSection {
            AppFactRow(RoseIcon.DESKTOP, "托盘图标", value, tone)
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

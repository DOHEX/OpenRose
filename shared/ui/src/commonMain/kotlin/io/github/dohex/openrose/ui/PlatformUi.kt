package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PlatformState

/** Settings retain their order while the host supplies its own platform sections. */
enum class PlatformSettingsPosition { BEFORE_CONNECTION, AFTER_CONNECTION }

/** Platform content is chosen by the host; shared pages only observe capabilities and control state. */
interface PlatformUi {
    val platformName: String
    val requestBluetoothPermission: (() -> Unit)? get() = null
    val openBluetoothSettings: (() -> Unit)? get() = null

    val headsetCirculation: CirculationClient?
        @Composable get() = null
    val desktopSound: DesktopSoundActions? get() = null

    /** Page supplied by hosts that can pair this audio source for headset circulation. */
    val circulationPairingContent: (@Composable () -> Unit)?
        @Composable get() = null

    /** 动态取色: whether the application's colors follow the system wallpaper instead of the style defaults. */
    val dynamicColor: Boolean
        @Composable get() = false

    /** Permission facts and fixes for the diagnostics page's 系统状态 group. */
    @Composable
    fun BluetoothPermissionRows(platform: PlatformState) {}

    /**
     * Why the host cannot control earphones although Bluetooth is usable, for the status hero; null when nothing on
     * its side stops it.
     */
    @Composable
    fun controlVerdict(state: ControlState): StatusVerdict? = null

    /** The saved 控制方式 shown on the status hero, where the host has one. */
    val controlWayLabel: String?
        @Composable get() = null

    /** The host's fact cards beneath the status hero and the shared connection facts. */
    @Composable
    fun StatusSections(state: ControlState) {}

    @Composable
    fun SettingsSections(state: ControlState, position: PlatformSettingsPosition) {}

    /** Rows the host adds to the 显示模式 group of 个性化. */
    @Composable
    fun DisplayModeRows() {}

    @Composable
    fun DiagnosticsStatusRows(state: ControlState) {}

    @Composable
    fun DiagnosticsSections(state: ControlState) {}

    @Composable
    fun DiagnosticsEntry(onDiagnostics: () -> Unit) {
        AppStatusSection {
            AppArrowRow(
                title = "诊断与记录",
                summary = "查看本平台状态与诊断信息",
                icon = RoseIcon.LOG,
                onClick = onDiagnostics,
            )
        }
    }

    @Composable
    fun StatusActions() {}

    @Composable
    fun HeadphoneActions() {}
}

/** A host with shared control UI and no additional integration sections. */
class BasicPlatformUi(override val platformName: String) : PlatformUi

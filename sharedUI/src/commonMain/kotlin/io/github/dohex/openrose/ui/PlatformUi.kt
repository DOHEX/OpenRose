package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PlatformState

/** Settings retain their order while the host supplies its own platform sections. */
enum class PlatformSettingsPosition { BEFORE_CONNECTION, AFTER_CONNECTION }

/** Platform content is chosen by the host; shared pages only observe capabilities and control state. */
interface PlatformUi {
    val platformName: String
    val requestBluetoothPermission: (() -> Unit)? get() = null
    val openBluetoothSettings: (() -> Unit)? get() = null

    /** 动态取色: whether the application's colors follow the system wallpaper instead of the style defaults. */
    val dynamicColor: Boolean
        @Composable get() = false

    @Composable
    fun BluetoothPermissionRows(platform: PlatformState, diagnostics: Boolean) {}

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
        AppGroup {
            AppArrowRow(
                title = "诊断与记录",
                summary = "查看本平台状态与诊断信息",
                value = "查看",
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

package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PlatformState

interface PlatformUi {
    val platformName: String
    val requestBluetoothPermission: (() -> Unit)? get() = null
    val openBluetoothSettings: (() -> Unit)? get() = null

    val headsetCirculation: CirculationClient?
        @Composable get() = null
    val desktopSound: DesktopSoundActions? get() = null

    val circulationPairingContent: (@Composable () -> Unit)?
        @Composable get() = null

    val trayPanelSettingsSummary: String?
        @Composable get() = null

    val trayPanelSettingsContent: (@Composable () -> Unit)?
        @Composable get() = null

    val systemIntegrationSummary: String?
        @Composable get() = null

    val systemIntegrationContent: (@Composable () -> Unit)?
        @Composable get() = null

    val hasControlSettings: Boolean
        @Composable get() = false

    val dynamicColor: Boolean
        @Composable get() = false

    @Composable
    fun BluetoothPermissionRows(platform: PlatformState) {}

    @Composable
    fun controlVerdict(state: ControlState): StatusVerdict? = null

    val controlWayLabel: String?
        @Composable get() = null

    @Composable
    fun StatusSections(state: ControlState) {}

    @Composable
    fun ControlSettingsRows(state: ControlState) {}

    @Composable
    fun BackgroundSettingsSection(state: ControlState) {}

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

class BasicPlatformUi(override val platformName: String) : PlatformUi

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.ui.model.OpenRoseModel

/** Settings content only. The application root owns the page title, navigation, and scrolling. */
@Composable
fun PreferencesPage(model: OpenRoseModel, platformUi: PlatformUi) {
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val onUiSettingsChange = model::updateUiSettings
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "界面风格") {
            AppChoiceRow(
                title = "组件风格",
                valueText = interfaceStyleLabel(uiSettings.interfaceStyle),
                options = listOf(
                    AppOption(InterfaceStyle.MIUIX, "Miuix"),
                    AppOption(InterfaceStyle.MATERIAL3, "Material 3 Expressive"),
                ),
                selected = uiSettings.interfaceStyle,
                onSelect = { onUiSettingsChange(uiSettings.copy(interfaceStyle = it)) },
            )
        }

        AppGroup(title = "显示模式") {
            AppChoiceRow(
                title = "明暗模式",
                valueText = appearanceLabel(uiSettings.appearance),
                options = Appearance.entries.map { AppOption(it, appearanceLabel(it)) },
                selected = uiSettings.appearance,
                onSelect = { onUiSettingsChange(uiSettings.copy(appearance = it)) },
            )
            AppChoiceRow(
                title = "底栏样式",
                valueText = bottomBarStyleLabel(uiSettings.bottomBarStyle),
                options = BottomBarStyle.entries.map { AppOption(it, bottomBarStyleLabel(it)) },
                selected = uiSettings.bottomBarStyle,
                onSelect = { onUiSettingsChange(uiSettings.copy(bottomBarStyle = it)) },
            )
            AppSwitchRow(
                title = "背景模糊",
                summary = "顶栏、底栏及浮层启用毛玻璃透视效果",
                checked = uiSettings.backgroundBlurEnabled,
                onChange = { onUiSettingsChange(uiSettings.copy(backgroundBlurEnabled = it)) },
            )
        }
        AppGroup(title = "动效") {
            AppChoiceRow(
                title = "转场动效",
                valueText = pageTransitionLabel(uiSettings.pageTransition),
                options = PageTransition.entries.map { AppOption(it, pageTransitionLabel(it)) },
                selected = uiSettings.pageTransition,
                onSelect = { onUiSettingsChange(uiSettings.copy(pageTransition = it)) },
            )
        }

        platformUi.SettingsSections(state, PlatformSettingsPosition.BEFORE_CONNECTION)

        if (state.platform.controlTransportApplicability == PlatformApplicability.APPLICABLE) {
            AppGroup(title = "连接偏好") {
                AppSwitchRow(
                    title = "强制使用 BLE",
                    summary = "仅影响下一次连接",
                    checked = state.settings.forceBle,
                    onChange = { model.updateSettings(state.settings.copy(forceBle = it)) },
                )
            }
        }

        platformUi.SettingsSections(state, PlatformSettingsPosition.AFTER_CONNECTION)

        AppGroup(title = "关于") {
            AppInfoRow(
                label = "关于 OpenRose",
                value = "ROSE / ROSESELSA 耳机控制",
            )
        }
    }
}

/** Read-only control facts and the host's integration status; preferences live in Settings. */
@Composable
fun StatusPage(model: OpenRoseModel, platformUi: PlatformUi, onDiagnostics: () -> Unit) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "蓝牙") {
            platformUi.BluetoothPermissionRows(state.platform, diagnostics = false)
            TransportRow(state.platform, platformUi.openBluetoothSettings)
        }
        platformUi.StatusSections(state)
        platformUi.DiagnosticsEntry(onDiagnostics)
    }
}

/** Common platform facts followed by diagnostics supplied by the host. */
@Composable
fun PlatformDiagnosticsCard(model: OpenRoseModel, platformUi: PlatformUi) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup(title = "系统状态") {
            AppInfoRow("当前平台", platformUi.platformName)
            platformUi.BluetoothPermissionRows(state.platform, diagnostics = true)
            TransportRow(state.platform, platformUi.openBluetoothSettings)
            platformUi.DiagnosticsStatusRows(state)
        }
        platformUi.DiagnosticsSections(state)
    }
}

@Composable
private fun TransportRow(platform: PlatformState, onOpenSystemSettings: (() -> Unit)?) {
    val transportValue = when (val transport = platform.transport) {
        TransportAvailability.Available -> "可用"
        TransportAvailability.Disabled -> "系统蓝牙已关闭"
        is TransportAvailability.Unavailable -> transport.reason
    }
    val transportTone = when (platform.transport) {
        TransportAvailability.Available -> StatusTone.GOOD
        TransportAvailability.Disabled -> StatusTone.CAUTION
        is TransportAvailability.Unavailable -> StatusTone.NEUTRAL
    }
    ToneInfoRow("蓝牙传输", transportValue, transportTone)
    if (platform.transport == TransportAvailability.Disabled && onOpenSystemSettings != null) {
        AppButton(
            text = "打开蓝牙设置",
            onClick = onOpenSystemSettings,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
fun ToneInfoRow(label: String, value: String, tone: StatusTone) {
    AppInfoRow(label = label, value = value, valueColor = tone.color())
}


enum class StatusTone {
    NEUTRAL,
    GOOD,
    CAUTION,
    ERROR;

    @Composable
    fun color(): Color = appColor(
        when (this) {
            NEUTRAL, CAUTION -> AppColorRole.Summary
            GOOD -> AppColorRole.Primary
            ERROR -> AppColorRole.Error
        },
    )
}

private fun interfaceStyleLabel(style: InterfaceStyle): String = when (style) {
    InterfaceStyle.MIUIX -> "Miuix"
    InterfaceStyle.MATERIAL3 -> "Material 3 Expressive"
}

private fun appearanceLabel(appearance: Appearance): String = when (appearance) {
    Appearance.SYSTEM -> "跟随系统"
    Appearance.LIGHT -> "浅色"
    Appearance.DARK -> "深色"
}

internal fun bottomBarStyleLabel(style: BottomBarStyle): String = when (style) {
    BottomBarStyle.FIXED -> "标准固定"
    BottomBarStyle.FLOATING -> "悬浮胶囊"
}

private fun pageTransitionLabel(transition: PageTransition): String = when (transition) {
    PageTransition.MIUIX_SPRING -> "Miuix 弹性滑动"
    PageTransition.SLIDE -> "标准平移"
    PageTransition.FADE -> "淡入淡出"
    PageTransition.NONE -> "关闭动效"
}


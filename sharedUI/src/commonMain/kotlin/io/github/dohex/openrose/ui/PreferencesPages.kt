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
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.ui.model.OpenRoseModel

/**
 * Settings content only. The application root owns the page title, navigation, and scrolling.
 * Interface preferences live one level down in [PersonalizationPage], the app's identity and links in [AboutPage].
 */
@Composable
fun PreferencesPage(model: OpenRoseModel, platformUi: PlatformUi, onPersonalization: () -> Unit, onAbout: () -> Unit) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppGroup {
            AppArrowRow(
                title = "个性化",
                summary = "组件风格、明暗模式、背景模糊与转场动效",
                icon = RoseIcon.PALETTE,
                onClick = onPersonalization,
            )
        }

        platformUi.SettingsSections(state, PlatformSettingsPosition.BEFORE_CONNECTION)

        if (state.platform.controlTransportApplicability == PlatformApplicability.APPLICABLE) {
            AppGroup(title = "高级") {
                AppSwitchRow(
                    title = "强制使用 BLE",
                    summary = "连接不稳定时再开启；仅影响下一次连接",
                    checked = state.settings.forceBle,
                    onChange = { model.updateSettings(state.settings.copy(forceBle = it)) },
                )
            }
        }

        platformUi.SettingsSections(state, PlatformSettingsPosition.AFTER_CONNECTION)

        AppGroup {
            AppArrowRow(
                title = "关于",
                summary = "版本、项目链接与致谢",
                icon = RoseIcon.INFO,
                onClick = onAbout,
            )
        }
    }
}

/**
 * Interface style and appearance preferences, reached from Settings.
 * [wide] mirrors the main shell: with the navigation rail shown, 底栏样式 has no effect and is hidden.
 */
@Composable
fun PersonalizationPage(model: OpenRoseModel, platformUi: PlatformUi, wide: Boolean = false) {
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
            platformUi.DisplayModeRows()
            if (!wide) {
                AppChoiceRow(
                    title = "底栏样式",
                    valueText = bottomBarStyleLabel(uiSettings.bottomBarStyle),
                    options = BottomBarStyle.entries.map { AppOption(it, bottomBarStyleLabel(it)) },
                    selected = uiSettings.bottomBarStyle,
                    onSelect = { onUiSettingsChange(uiSettings.copy(bottomBarStyle = it)) },
                )
                // Liquid glass is a Miuix-only capsule; Material 3's floating toolbar has no second material.
                if (uiSettings.interfaceStyle == InterfaceStyle.MIUIX && uiSettings.bottomBarStyle == BottomBarStyle.FLOATING) {
                    AppChoiceRow(
                        title = "悬浮底栏材质",
                        valueText = floatingBarMaterialLabel(uiSettings.floatingBarMaterial),
                        options = FloatingBarMaterial.entries.map { AppOption(it, floatingBarMaterialLabel(it)) },
                        selected = uiSettings.floatingBarMaterial,
                        onSelect = { onUiSettingsChange(uiSettings.copy(floatingBarMaterial = it)) },
                    )
                }
            }
            AppSwitchRow(
                title = "背景模糊",
                summary = "顶栏、底栏及浮层启用毛玻璃透视效果",
                checked = uiSettings.backgroundBlurEnabled,
                onChange = { onUiSettingsChange(uiSettings.copy(backgroundBlurEnabled = it)) },
            )
        }
        AppGroup(title = "动效", footnote = "转场动效作用于进入和返回二级页面；关闭动效时，主页面切换也不播放动画。") {
            AppChoiceRow(
                title = "转场动效",
                valueText = pageTransitionLabel(uiSettings.pageTransition),
                options = PageTransition.entries.map { AppOption(it, pageTransitionLabel(it)) },
                selected = uiSettings.pageTransition,
                onSelect = { onUiSettingsChange(uiSettings.copy(pageTransition = it)) },
            )
        }
    }
}

/**
 * Whether OpenRose can control earphones, laid out like KernelSU's home: one verdict with its fixes, then the facts
 * behind it. The verdict is the only card that judges; a host fact that does not stop control stays a fact row.
 * Read-only apart from those fixes; preferences live in Settings.
 */
@Composable
fun StatusPage(model: OpenRoseModel, platformUi: PlatformUi, onDiagnostics: () -> Unit) {
    val state by model.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppStatusHero(statusVerdict(state, platformUi), platformUi.controlWayLabel)
        AppStatusSection {
            val connection = state.connection
            Tagged(StatusTags.CONNECTION) {
                AppFactRow(
                    RoseIcon.HEADPHONES,
                    "耳机控制连接",
                    connectionLabel(connection),
                    if ((connection as? ConnectionState.Connected)?.phase == ConnectionPhase.READY) StatusTone.GOOD else StatusTone.NEUTRAL,
                )
            }
            state.controlTransportLabel()?.let { AppFactRow(RoseIcon.LINK, "控制传输", it) }
            when (state.platform.transport) {
                TransportAvailability.Available -> AppFactRow(RoseIcon.BLUETOOTH, "蓝牙传输", "可用", StatusTone.GOOD)
                TransportAvailability.Disabled -> AppFactRow(RoseIcon.BLUETOOTH, "蓝牙传输", "已关闭", StatusTone.CAUTION)
                is TransportAvailability.Unavailable -> AppFactRow(RoseIcon.BLUETOOTH, "蓝牙传输", "不可用", StatusTone.ERROR)
            }
        }
        platformUi.StatusSections(state)
        platformUi.DiagnosticsEntry(onDiagnostics)
    }
}

object StatusTags {
    const val CONNECTION = "status.connection"
}

/** Bluetooth comes first, since nothing works without it; then whatever stops the host; otherwise OpenRose works. */
@Composable
private fun statusVerdict(state: ControlState, platformUi: PlatformUi): StatusVerdict {
    val permission = state.platform.permission
    val transport = state.platform.transport
    val permissionMissing = state.platform.bluetoothPermissionRequired
    val fixes = listOfNotNull(
        platformUi.requestBluetoothPermission?.takeIf { permissionMissing }?.let { AppStatusAction("授予蓝牙权限", it) },
        platformUi.openBluetoothSettings?.takeIf { transport == TransportAvailability.Disabled }?.let { AppStatusAction("打开蓝牙设置", it) },
    )
    return when {
        permission == PermissionState.DENIED -> StatusVerdict(AppStatusKind.Error, "未获得蓝牙权限", "授予权限后才能连接耳机", fixes)
        permission == PermissionState.REQUIRED -> StatusVerdict(AppStatusKind.Caution, "需要蓝牙权限", "授予权限后才能连接耳机", fixes)
        transport is TransportAvailability.Unavailable -> StatusVerdict(AppStatusKind.Error, "蓝牙不可用", transport.reason)
        transport == TransportAvailability.Disabled -> StatusVerdict(AppStatusKind.Caution, "系统蓝牙已关闭", "打开系统蓝牙后才能连接耳机", fixes)
        else -> platformUi.controlVerdict(state) ?: StatusVerdict(AppStatusKind.Good, "工作中", "版本 ${AppVersion.NAME}")
    }
}

private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> "未连接"

    is ConnectionState.Connecting -> "连接中"

    is ConnectionState.Connected -> buildString {
        append("已连接 · ").append(connection.profile.shortLabel).append(' ').append(connection.profile.family.displayName)
        if (connection.phase != ConnectionPhase.READY) append("（正在读取状态）")
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
            platformUi.BluetoothPermissionRows(state.platform)
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
    ERROR,
    ;

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

private fun floatingBarMaterialLabel(material: FloatingBarMaterial): String = when (material) {
    FloatingBarMaterial.FROSTED -> "毛玻璃"
    FloatingBarMaterial.LIQUID_GLASS -> "液态玻璃"
}

private fun pageTransitionLabel(transition: PageTransition): String = when (transition) {
    PageTransition.MIUIX_SPRING -> "Miuix 弹性滑动"
    PageTransition.SLIDE -> "标准平移"
    PageTransition.FADE -> "淡入淡出"
    PageTransition.NONE -> "关闭动效"
}

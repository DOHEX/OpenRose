package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.IslandTrigger
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/**
 * Renders an externally owned control session. This function never creates a session or transport;
 * replacing a component style cannot restart connections or replay commands.
 */
@Composable
fun OpenRoseApp(
    state: ControlState,
    onSettingsChange: (UserSettings) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DeviceId) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val dark = when (settings.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    // Retain scroll position outside the style branch as well as the externally owned session.
    val scrollState = rememberScrollState()
    val content: @Composable () -> Unit = {
        Box(
            modifier.fillMaxSize().background(pageBackground()).safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 1080.dp).fillMaxSize()
                    .verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("OpenRose", large = true)
                    AppText("ROSE / ROSESELSA 耳机控制", secondary = true)
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 800.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                DevicePanel(state, onStartScan, onStopScan, onConnect, onDisconnect)
                                PlatformCard(state.platform, onRequestPermission, onOpenSystemSettings)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                AppearanceCard(settings, onSettingsChange)
                                IntegrationCard(settings, state.platform.module, onSettingsChange)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            DevicePanel(state, onStartScan, onStopScan, onConnect, onDisconnect)
                            PlatformCard(state.platform, onRequestPermission, onOpenSystemSettings)
                            AppearanceCard(settings, onSettingsChange)
                            IntegrationCard(settings, state.platform.module, onSettingsChange)
                        }
                    }
                }
                AppText("系统适配与各型号实机验证进度分别记录。", secondary = true)
            }
        }
    }
    CompositionLocalProvider(LocalInterfaceStyle provides settings.interfaceStyle) {
        when (settings.interfaceStyle) {
            InterfaceStyle.MIUIX -> MiuixTheme(
                colors = if (dark) miuixDarkColorScheme() else miuixLightColorScheme(),
                content = content,
            )
            InterfaceStyle.MATERIAL3 -> MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
                content = content,
            )
        }
    }
}

@Composable
private fun PlatformCard(
    platform: PlatformState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    AppCard {
        AppText("运行状态", title = true)
        Spacer(Modifier.height(20.dp))
        AppText("蓝牙权限")
        AppText(
            when (platform.permission) {
                PermissionState.NOT_REQUIRED -> "此平台无需应用内授权"
                PermissionState.GRANTED -> "已授权"
                PermissionState.REQUIRED -> "需要授权后才能使用蓝牙"
                PermissionState.DENIED -> "未获得蓝牙权限"
            },
            secondary = true,
        )
        if (platform.permission == PermissionState.REQUIRED || platform.permission == PermissionState.DENIED) {
            Spacer(Modifier.height(8.dp))
            AppButton("授予蓝牙权限", onRequestPermission)
        }
        Spacer(Modifier.height(20.dp))
        AppText("蓝牙传输")
        AppText(
            when (val transport = platform.transport) {
                TransportAvailability.Available -> "可用"
                TransportAvailability.Disabled -> "系统蓝牙已关闭"
                is TransportAvailability.Unavailable -> transport.reason
            },
            secondary = true,
        )
        if (platform.transport == TransportAvailability.Disabled) {
            Spacer(Modifier.height(8.dp))
            AppButton("打开蓝牙设置", onOpenSystemSettings)
        }
        Spacer(Modifier.height(20.dp))
        AppText("系统模块")
        AppText(
            when (val module = platform.module) {
                ModuleAvailability.Available -> "已连接系统模块"
                ModuleAvailability.NotEnabled -> "系统模块未启用"
                is ModuleAvailability.Unavailable -> module.reason
            },
            secondary = true,
        )
    }
}

@Composable
private fun AppearanceCard(settings: UserSettings, onChange: (UserSettings) -> Unit) {
    AppCard {
        AppText("界面", title = true)
        Spacer(Modifier.height(16.dp))
        AppText("界面风格")
        Spacer(Modifier.height(8.dp))
        Choices(
            InterfaceStyle.entries,
            settings.interfaceStyle,
            { if (it == InterfaceStyle.MIUIX) "Miuix" else "Material 3" },
        ) { onChange(settings.copy(interfaceStyle = it)) }
        Spacer(Modifier.height(8.dp))
        AppText("即时切换组件风格，控制会话保持不变。", secondary = true)
        Spacer(Modifier.height(20.dp))
        AppText("显示模式")
        Spacer(Modifier.height(8.dp))
        Choices(Appearance.entries, settings.appearance, {
            when (it) {
                Appearance.SYSTEM -> "跟随系统"
                Appearance.LIGHT -> "浅色"
                Appearance.DARK -> "深色"
            }
        }) { onChange(settings.copy(appearance = it)) }
    }
}

@Composable
private fun IntegrationCard(
    settings: UserSettings,
    module: ModuleAvailability,
    onChange: (UserSettings) -> Unit,
) {
    AppCard {
        AppText("系统呈现", title = true)
        Spacer(Modifier.height(8.dp))
        if (module != ModuleAvailability.Available) {
            AppText("系统集成未就绪，以下偏好尚不产生系统展示。", secondary = true)
            Spacer(Modifier.height(16.dp))
        }
        AppText("超级岛")
        Spacer(Modifier.height(8.dp))
        Choices(IslandMode.entries, settings.islandMode, {
            when (it) {
                IslandMode.OFF -> "不显示"
                IslandMode.OFFICIAL -> "官方"
                IslandMode.MODULE -> "模块"
            }
        }) { onChange(settings.copy(islandMode = it)) }
        Spacer(Modifier.height(8.dp))
        AppText("官方沿用系统耳机呈现，模块使用 OpenRose 样式；均由系统超级岛展示。", secondary = true)
        Spacer(Modifier.height(20.dp))
        ToggleRow("焦点通知", "连接期间保留耳机状态通知", settings.focusNotificationEnabled) {
            onChange(settings.copy(focusNotificationEnabled = it))
        }
        if (settings.islandMode == IslandMode.MODULE) {
            Spacer(Modifier.height(20.dp))
            AppText("超级岛显示时机")
            Spacer(Modifier.height(8.dp))
            ToggleRow("连接时", "新连接会话开始时提示", IslandTrigger.CONNECTION in settings.islandTriggers) { enabled ->
                val triggers = if (enabled) settings.islandTriggers + IslandTrigger.CONNECTION
                else settings.islandTriggers - IslandTrigger.CONNECTION
                onChange(settings.copy(islandTriggers = triggers))
            }
            if (settings.islandTriggers.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                AppText("未选择显示时机，不自动触发。", secondary = true)
            }
            Spacer(Modifier.height(12.dp))
            AppText("佩戴、摘下与入盒事件尚无可用型号支持，暂不开放。", secondary = true)
        }
    }
}

@Composable
private fun ToggleRow(label: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            AppText(label)
            AppText(summary, secondary = true)
        }
        // The entire labelled row is the input target, including keyboard and accessibility input.
        AppSwitch(checked, null, Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onChange: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            AppButton(label(option), { onChange(option) }, selected = option == selected, choice = true)
        }
    }
}

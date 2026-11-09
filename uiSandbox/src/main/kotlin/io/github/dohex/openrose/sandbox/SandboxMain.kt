package io.github.dohex.openrose.sandbox

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.DecoratedWindowDefaults
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.InterfaceStyle
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.ui.PrimaryDeviceSelection
import io.github.dohex.openrose.ui.TrayPanel
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.interfaceBackgroundColor

/** Preview sizes in dp: a phone, the narrow layout the UI tests use, a tablet, and the desktop window's default. */
private val previewSizes = listOf(
    "手机" to DpSize(360.dp, 780.dp),
    "窄屏" to DpSize(420.dp, 900.dp),
    "平板" to DpSize(840.dp, 1000.dp),
    "桌面" to DpSize(1180.dp, 820.dp),
)

/** The width the desktop app gives its tray popup, and the height it starts from before fitting it to the content. */
private val trayPanelSize = DpSize(360.dp, 440.dp)

/**
 * A control window that edits one [SandboxScenario], a preview window running the shared app on it, and on desktop
 * hosts the tray panel. Every window shares one model, so a write in either one shows in both. The windows open on
 * the desktop app's backend; it is a separate instance, so a running OpenRose is left alone.
 */
fun main(args: Array<String>) = nucleusApplication(args = args, backend = NucleusBackend.Tao, enableSingleInstance = false) {
    val scope = rememberCoroutineScope()
    val model = remember { SandboxModel(scope) }
    val platforms = remember { SandboxPlatforms(model, scope) }
    val selection = remember { PrimaryDeviceSelection() }
    val scenario by model.scenario.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val dark = when (uiSettings.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val controlColors = if (dark) darkColorScheme() else lightColorScheme()
    val appBackground = interfaceBackgroundColor(uiSettings.interfaceStyle, dark)
    val preview = rememberWindowState(size = previewSizes[1].second, position = WindowPosition(440.dp, 40.dp))
    var trayShown by remember { mutableStateOf(true) }

    SandboxWindow(
        title = "OpenRose UI 沙盒",
        state = rememberWindowState(size = DpSize(420.dp, 900.dp), position = WindowPosition(0.dp, 40.dp)),
        dark = dark,
        background = controlColors.surface,
        onCloseRequest = ::exitApplication,
    ) {
        SandboxControls(model, scenario, uiSettings, controlColors, preview, trayShown) { trayShown = it }
    }
    SandboxWindow(
        title = "预览 · ${scenario.profile.displayName} · ${scenario.platformName} · ${uiSettings.interfaceStyle.label}",
        state = preview,
        dark = dark,
        background = appBackground,
        onCloseRequest = ::exitApplication,
    ) {
        // Each host is its own app: switching it starts from the first page, as launching that app would.
        key(scenario.host) {
            OpenRoseApp(model = model, platformUi = platforms.forScenario(scenario), primaryDeviceSelection = selection)
        }
    }
    if (scenario.desktop && trayShown) {
        val trayState = rememberWindowState(size = trayPanelSize, position = WindowPosition(1640.dp, 40.dp))
        SandboxWindow(
            title = "托盘面板",
            state = trayState,
            dark = dark,
            background = appBackground,
            onCloseRequest = { trayShown = false },
            resizable = false,
        ) {
            TrayPanel(
                model = model,
                selection = selection,
                onOpenApp = { preview.isMinimized = false },
                onContentHeight = { trayState.size = DpSize(trayPanelSize.width, it) },
            )
        }
    }
}

/** A window dressed the way the desktop app dresses its own: a title bar in the content's background color. */
@Composable
private fun NucleusApplicationScope.SandboxWindow(
    title: String,
    state: WindowState,
    dark: Boolean,
    background: Color,
    onCloseRequest: () -> Unit,
    resizable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val titleBarStyle = if (dark) DecoratedWindowDefaults.darkTitleBarStyle() else DecoratedWindowDefaults.lightTitleBarStyle()
    NucleusDecoratedWindowTheme(
        isDark = dark,
        titleBarStyle = titleBarStyle.copy(
            colors = titleBarStyle.colors.copy(
                background = background,
                inactiveBackground = background,
                border = Color.Transparent,
                fullscreenControlButtonsBackground = background,
            ),
        ),
    ) {
        DecoratedWindow(onCloseRequest = onCloseRequest, state = state, title = title, resizable = resizable) {
            TitleBar {
                BasicText(title, style = TextStyle(color = LocalTitleBarStyle.current.colors.content))
            }
            content()
        }
    }
}

@Composable
private fun SandboxControls(
    model: SandboxModel,
    scenario: SandboxScenario,
    uiSettings: UiSettings,
    colors: ColorScheme,
    preview: WindowState,
    trayShown: Boolean,
    onTrayShown: (Boolean) -> Unit,
) {
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScenarioChoice(model, "平台", sandboxHosts, scenario.host, { it.label }) { onHost(it) }
                ScenarioChoice(model, "型号配置", ModelProfile.entries, scenario.profile, { it.displayName }) { copy(profile = it) }
                Section("固件") {
                    OutlinedTextField(
                        value = scenario.firmware,
                        onValueChange = { text -> model.updateScenario { it.copy(firmware = text) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("留空表示未读取") },
                        supportingText = { Text(capabilitySummary(scenario)) },
                    )
                }
                ScenarioChoice(model, "控制连接", SandboxLink.entries, scenario.link, { it.label }) { copy(link = it) }
                ScenarioChoice(model, "电量", SandboxBattery.entries, scenario.battery, { it.label }) { copy(battery = it) }
                ScenarioChoice(model, "平台状况", SandboxPlatformIssue.entries, scenario.issue, { it.label }) { copy(issue = it) }
                ScenarioChoice(model, "设置写入", SandboxWriteOutcome.entries, scenario.writeOutcome, { it.label }) { copy(writeOutcome = it) }
                Section("界面") {
                    Choices(InterfaceStyle.entries, uiSettings.interfaceStyle, { it.label }) {
                        model.updateUiSettings(uiSettings.copy(interfaceStyle = it))
                    }
                    Choices(Appearance.entries, uiSettings.appearance, { it.label }) {
                        model.updateUiSettings(uiSettings.copy(appearance = it))
                    }
                }
                Section("预览窗口") {
                    val current = previewSizes.firstOrNull { it.second == preview.size }?.first
                    Choices(previewSizes.map { it.first }, current, { it }) { name ->
                        preview.size = previewSizes.first { it.first == name }.second
                    }
                    Text(
                        "当前 ${preview.size.width.value.toInt()} × ${preview.size.height.value.toInt()} dp；也可直接拖动窗口边缘",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (scenario.host == HostPlatform.ANDROID) AndroidControls(model, scenario)
                if (scenario.desktop) DesktopControls(model, scenario, trayShown, onTrayShown)
            }
        }
    }
}

@Composable
private fun AndroidControls(model: SandboxModel, scenario: SandboxScenario) {
    HorizontalDivider()
    Section("Android 系统集成") {
        Toggle("HyperOS 系统集成适用", scenario.hyperOs) { on -> model.updateScenario { it.copy(hyperOs = on) } }
        Choices(SandboxModule.entries, scenario.module, { it.label }) { module -> model.updateScenario { it.copy(module = module) } }
    }
    ScenarioChoice(model, "所选控制方式的所有者", SandboxOwner.entries, scenario.owner, { it.label }) { copy(owner = it) }
    ScenarioChoice(model, "后台控制服务", SandboxBackgroundService.entries, scenario.backgroundService, { it.label }) {
        copy(backgroundService = it)
    }
}

@Composable
private fun DesktopControls(model: SandboxModel, scenario: SandboxScenario, trayShown: Boolean, onTrayShown: (Boolean) -> Unit) {
    HorizontalDivider()
    Section("托盘") {
        Choices(SandboxTray.entries, scenario.tray, { it.label }) { tray -> model.updateScenario { it.copy(tray = tray) } }
        Toggle("显示托盘面板", trayShown, onTrayShown)
    }
    ScenarioChoice(model, "登录时启动", SandboxLoginLaunch.entries, scenario.loginLaunch, { it.label }) { copy(loginLaunch = it) }
}

/** One titled row of options that each set one scenario fact. */
@Composable
private fun <T> ScenarioChoice(
    model: SandboxModel,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    change: SandboxScenario.(T) -> SandboxScenario,
) {
    Section(title) {
        Choices(options, selected, label) { option -> model.updateScenario { it.change(option) } }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in options) {
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** What the firmware text resolves to, and the settings the model then offers, so firmware gates are visible. */
private fun capabilitySummary(scenario: SandboxScenario): String {
    val firmware = scenario.firmwareValue
    val writable = scenario.profile.capabilities(firmware).writableSettings.sortedBy { it.ordinal }
    val read = firmware?.let { "固件 ${it.displayText}" } ?: "未读取固件"
    return "$read · 可写 ${writable.size} 项：${writable.joinToString("、") { it.name.lowercase() }}"
}

private val sandboxHosts = listOf(HostPlatform.ANDROID, HostPlatform.LINUX, HostPlatform.WINDOWS)

private val HostPlatform.label: String
    get() = when (this) {
        HostPlatform.ANDROID -> "Android"
        HostPlatform.LINUX -> "Linux"
        HostPlatform.WINDOWS -> "Windows"
        HostPlatform.UNKNOWN -> "未知"
    }

private val InterfaceStyle.label: String
    get() = when (this) {
        InterfaceStyle.MIUIX -> "Miuix"
        InterfaceStyle.MATERIAL3 -> "Material 3"
    }

private val Appearance.label: String
    get() = when (this) {
        Appearance.SYSTEM -> "跟随系统"
        Appearance.LIGHT -> "浅色"
        Appearance.DARK -> "深色"
    }

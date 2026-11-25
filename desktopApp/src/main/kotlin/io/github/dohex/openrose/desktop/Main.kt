package io.github.dohex.openrose.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.SingleInstanceRestoreEffect
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.window.DecoratedWindowDefaults
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.TransportUnavailableCode
import io.github.dohex.openrose.transport.bluez.BluezControlTransport
import io.github.dohex.openrose.transport.bluez.linuxBluetoothSettingsCommand
import io.github.dohex.openrose.transport.windows.WindowsControlTransport
import io.github.dohex.openrose.transport.windows.windowsBluetoothSettingsCommand
import io.github.dohex.openrose.ui.AppLogo
import io.github.dohex.openrose.ui.LocalCirculationVisible
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.desktop.DesktopPlatformUi
import io.github.dohex.openrose.ui.desktop.TrayAvailability
import io.github.dohex.openrose.ui.interfaceBackgroundColor
import io.github.dohex.openrose.ui.isDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private val MAIN_WINDOW_SIZE = DpSize(1180.dp, 820.dp)

private val MAIN_WINDOW_MINIMUM_SIZE = DpSize(420.dp, 560.dp)

fun main(args: Array<String>) {
    val platform = System.getProperty("os.name")
    val hostPlatform = when {
        platform.contains("windows", ignoreCase = true) -> HostPlatform.WINDOWS
        platform.contains("linux", ignoreCase = true) -> HostPlatform.LINUX
        else -> HostPlatform.UNKNOWN
    }
    val startedAtLogin = runCatching { AutoLaunch.wasStartedAtLogin(args) }.getOrDefault(false)
    var owner: DesktopRuntime? = null
    var trayOwner: TrayMonitor? = null
    suspend fun closeResources() {
        try {
            owner?.close()
        } finally {
            owner = null
            trayOwner?.close()
            trayOwner = null
        }
    }

    try {
        nucleusApplication(args = args, backend = NucleusBackend.Tao, enableSingleInstance = true) {
            val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            val runtime = remember { startRuntime(platform, hostPlatform, scope).also { owner = it } }
            val tray = remember { trayMonitor(hostIsLinux = hostPlatform == HostPlatform.LINUX).also { trayOwner = it } }
            DesktopWindows(runtime, tray, scope, platform, hostPlatform, startedAtLogin, ::closeResources)
        }
    } finally {
        runBlocking { closeResources() }
    }
}

private fun startRuntime(platform: String, hostPlatform: HostPlatform, scope: CoroutineScope): DesktopRuntime {
    val transport = when (hostPlatform) {
        HostPlatform.LINUX -> BluezControlTransport(scope)
        HostPlatform.WINDOWS -> WindowsControlTransport(scope)
        else -> null
    }
    return DesktopRuntime(
        paths = desktopPaths(hostPlatform),
        platform = PlatformState(
            permission = PermissionState.NOT_REQUIRED,
            transport = transport?.availability?.value
                ?: TransportAvailability.Unavailable(TransportUnavailableCode.UNSUPPORTED_PLATFORM, platform),
            hostPlatform = hostPlatform,
            controlTransportApplicability = if (transport != null) {
                PlatformApplicability.APPLICABLE
            } else {
                PlatformApplicability.NOT_APPLICABLE
            },
        ),
        scope = scope,
        transport = transport,
    )
}

@Composable
private fun NucleusApplicationScope.DesktopWindows(
    runtime: DesktopRuntime,
    tray: TrayMonitor,
    scope: CoroutineScope,
    platform: String,
    hostPlatform: HostPlatform,
    startedAtLogin: Boolean,
    closeResources: suspend () -> Unit,
) {
    val loginLaunch = remember { LoginLaunch(scope) }
    val circulation = remember(runtime) { runtime.startCirculation() }
    val platformUi = remember {
        DesktopPlatformUi(
            platformName = platform,
            desktopSettings = runtime.desktopSettings,
            onDesktopSettingsChange = runtime::updateDesktopSettings,
            tray = tray.availability,
            loginLaunch = loginLaunch,
            circulation = circulation,
            desktopSound = runtime.sound,
            openBluetoothSettings = bluetoothSettingsAction(hostPlatform),
            showQr = { PairingQr(it) },
        )
    }
    val uiSettings by runtime.model.uiSettings.collectAsState()
    val desktopSettings by runtime.desktopSettings.collectAsState()
    val trayAvailability by tray.availability.collectAsState()
    val uiScope = rememberCoroutineScope()
    val window = remember {
        DesktopWindow(
            runtime,
            tray,
            uiScope,
            visibleAtLaunch = !(startedAtLogin && runtime.desktopSettings.value.backgroundControl && tray.nativeReady),
        ) { fromTray ->
            try {
                closeResources()
                if (fromTray && hostPlatform == HostPlatform.LINUX) exitProcess(0)
            } finally {
                exitApplication()
            }
        }
    }
    SingleInstanceRestoreEffect { window.show() }
    LaunchedEffect(window.visible, trayAvailability) {
        if (!window.visible && trayAvailability is TrayAvailability.Unavailable) window.show()
    }

    DesktopWindowTheme(uiSettings) {
        if (tray.nativeReady) {
            DesktopTray(
                host = remember { trayPanelHost() },
                model = runtime.model,
                uiScope = uiScope,
                circulation = circulation,
                sound = runtime.sound,
                trayPanelSettings = desktopSettings.trayPanel,
                onOpenApp = window::show,
                onExit = { window.exit(fromTray = true) },
                onStarted = tray::trayStarted,
            )
        }
        val screen = remember { usableScreenSize() }
        val initialSize = remember { MAIN_WINDOW_SIZE.fitTo(screen) }
        val minimumSize = remember { MAIN_WINDOW_MINIMUM_SIZE.fitTo(screen) }
        DecoratedWindow(
            title = "OpenRose",
            icon = AppLogo.painter,
            visible = window.visible,
            onCloseRequest = window::closeRequested,
            state = rememberWindowState(size = initialSize),
            minimumSize = minimumSize,
        ) {
            val current = nucleusWindow
            val focused by current.focusFlow.collectAsState()
            DisposableEffect(current) {
                window.native = current
                onDispose { if (window.native === current) window.native = null }
            }
            TitleBar {
                BasicText("OpenRose", style = TextStyle(color = LocalTitleBarStyle.current.colors.content))
            }
            CompositionLocalProvider(LocalCirculationVisible provides (window.visible && focused)) {
                OpenRoseApp(model = runtime.model, platformUi = platformUi)
            }
        }
    }
}

private class DesktopWindow(
    private val runtime: DesktopRuntime,
    private val tray: TrayMonitor,
    private val scope: CoroutineScope,
    visibleAtLaunch: Boolean,
    private val shutdown: suspend (fromTray: Boolean) -> Unit,
) {
    var visible by mutableStateOf(visibleAtLaunch)
        private set
    var native: NucleusWindow? = null
    private var exiting = false
    private var closing: Job? = null

    fun show() {
        closing?.cancel()
        visible = true
        native?.run {
            setMinimized(false)
            toFront()
            requestFocus()
        }
    }

    fun closeRequested() {
        if (!runtime.desktopSettings.value.backgroundControl) {
            exit()
        } else if (!exiting && closing?.isActive != true) {
            closing = scope.launch {
                if (tray.refresh() == TrayAvailability.Available && runtime.desktopSettings.value.backgroundControl) {
                    visible = false
                } else {
                    exit()
                }
            }
        }
    }

    fun exit(fromTray: Boolean = false) {
        if (exiting) return
        exiting = true
        closing?.cancel()
        scope.launch { shutdown(fromTray) }
    }
}

@Composable
private fun DesktopWindowTheme(uiSettings: UiSettings, content: @Composable () -> Unit) {
    val dark = uiSettings.appearance.isDark()
    val background = interfaceBackgroundColor(uiSettings.interfaceStyle, dark)
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
        content = content,
    )
}

private fun bluetoothSettingsAction(platform: HostPlatform): (() -> Unit)? {
    val command = when (platform) {
        HostPlatform.LINUX -> linuxBluetoothSettingsCommand()
        HostPlatform.WINDOWS -> windowsBluetoothSettingsCommand()
        else -> null
    } ?: return null
    return {
        runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }
}

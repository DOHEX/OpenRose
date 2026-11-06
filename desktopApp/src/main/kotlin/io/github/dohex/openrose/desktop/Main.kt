package io.github.dohex.openrose.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
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
import io.github.dohex.openrose.transport.bluez.BluezControlTransport
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.ui.PrimaryDeviceSelection
import io.github.dohex.openrose.ui.interfaceBackgroundColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.prefs.Preferences

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
    try {
        nucleusApplication(args = args, backend = NucleusBackend.Tao, enableSingleInstance = true) {
            val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            val runtime = remember {
                val transport = if (hostPlatform == HostPlatform.LINUX) BluezControlTransport(scope) else null
                DesktopRuntime(
                    preferences = Preferences.userRoot().node("io/github/dohex/openrose"),
                    platform = PlatformState(
                        permission = PermissionState.NOT_REQUIRED,
                        transport = transport?.availability?.value
                            ?: TransportAvailability.Unavailable("$platform 控制传输尚未实现"),
                        hostPlatform = hostPlatform,
                        controlTransportApplicability = if (transport != null) {
                            PlatformApplicability.APPLICABLE
                        } else {
                            PlatformApplicability.NOT_APPLICABLE
                        },
                    ),
                    scope = scope,
                    transport = transport,
                ).also { owner = it }
            }
            val tray = remember { trayMonitor(hostIsLinux = hostPlatform == HostPlatform.LINUX).also { trayOwner = it } }
            val loginLaunch = remember { LoginLaunch(scope) }
            val platformUi = remember {
                DesktopPlatformUi(
                    platformName = platform,
                    desktopSettings = runtime.desktopSettings,
                    onDesktopSettingsChange = runtime::updateDesktopSettings,
                    tray = tray.availability,
                    loginLaunch = loginLaunch,
                )
            }
            // One choice for the 未连接看板 and the tray, so “重新连接” targets the device the window shows.
            val selection = remember { PrimaryDeviceSelection() }
            val uiSettings by runtime.model.uiSettings.collectAsState()
            val desktopSettings by runtime.desktopSettings.collectAsState()
            val trayAvailability by tray.availability.collectAsState()
            val uiScope = rememberCoroutineScope()
            // A login launch stays in the tray only when closing the window would have kept it there.
            var windowVisible by remember {
                mutableStateOf(!(startedAtLogin && desktopSettings.backgroundControl && tray.nativeReady))
            }
            var window by remember { mutableStateOf<NucleusWindow?>(null) }
            var exiting by remember { mutableStateOf(false) }

            fun showWindow() {
                windowVisible = true
                window?.run {
                    setMinimized(false)
                    toFront()
                    requestFocus()
                }
            }

            fun exit() {
                if (exiting) return
                exiting = true
                uiScope.launch {
                    try {
                        runtime.close()
                    } finally {
                        owner = null
                        tray.close()
                        trayOwner = null
                        exitApplication()
                    }
                }
            }

            SingleInstanceRestoreEffect { showWindow() }
            // A hidden window must stay reachable: reveal it once the tray stops showing OpenRose.
            LaunchedEffect(windowVisible, trayAvailability) {
                if (!windowVisible && trayAvailability is TrayAvailability.Unavailable) showWindow()
            }

            val dark = when (uiSettings.appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
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
            ) {
                if (tray.nativeReady) {
                    DesktopTray(
                        host = remember { trayPanelHost() },
                        model = runtime.model,
                        selection = selection,
                        uiScope = uiScope,
                        onOpenApp = ::showWindow,
                        onExit = ::exit,
                        onStarted = tray::trayStarted,
                    )
                }
                DecoratedWindow(
                    title = "OpenRose",
                    visible = windowVisible,
                    onCloseRequest = {
                        // Hiding is safe only while the tray actually shows OpenRose; otherwise end the control connection.
                        if (desktopSettings.backgroundControl && tray.refresh() == TrayAvailability.Available) {
                            windowVisible = false
                        } else {
                            exit()
                        }
                    },
                    state = rememberWindowState(width = 1180.dp, height = 820.dp),
                ) {
                    val current = nucleusWindow
                    DisposableEffect(current) {
                        window = current
                        onDispose { if (window === current) window = null }
                    }
                    TitleBar {
                        BasicText("OpenRose", style = TextStyle(color = LocalTitleBarStyle.current.colors.content))
                    }
                    OpenRoseApp(model = runtime.model, platformUi = platformUi, primaryDeviceSelection = selection)
                }
            }
        }
    } finally {
        owner?.let { runtime -> runBlocking { runtime.close() } }
        trayOwner?.close()
    }
}

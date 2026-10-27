package io.github.dohex.openrose.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.DecoratedWindowDefaults
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.ControlSession
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.ui.interfaceBackgroundColor
import java.util.prefs.Preferences

fun main() {
    val preferences = Preferences.userRoot().node("io/github/dohex/openrose")
    val platform = System.getProperty("os.name")
    val hostPlatform = when {
        platform.contains("windows", ignoreCase = true) -> HostPlatform.WINDOWS
        platform.contains("linux", ignoreCase = true) -> HostPlatform.LINUX
        else -> HostPlatform.UNKNOWN
    }
    // Created before composition: window/style changes cannot create another control owner.
    val session = ControlSession(
        initialSettings = UserSettingsCodec.decode(preferences.keys().associateWith { preferences.get(it, "") }),
        initialPlatformState = PlatformState(
            permission = PermissionState.NOT_REQUIRED,
            transport = TransportAvailability.Unavailable("$platform 控制传输尚未实现"),
            module = ModuleAvailability.Unavailable("HyperOS 系统集成仅适用于 Android"),
            hostPlatform = hostPlatform,
            systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
            controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
        ),
    )
    nucleusApplication(backend = NucleusBackend.Tao, enableSingleInstance = false) {
        val state by session.state.collectAsState()
        val dark = when (state.settings.appearance) {
            Appearance.SYSTEM -> isSystemInDarkTheme()
            Appearance.LIGHT -> false
            Appearance.DARK -> true
        }
        val background = interfaceBackgroundColor(state.settings.interfaceStyle, dark)
        val titleBarStyle = if (dark) DecoratedWindowDefaults.darkTitleBarStyle() else DecoratedWindowDefaults.lightTitleBarStyle()
        NucleusDecoratedWindowTheme(
            isDark = dark,
            titleBarStyle = titleBarStyle.copy(colors = titleBarStyle.colors.copy(
                background = background,
                inactiveBackground = background,
                border = Color.Transparent,
                fullscreenControlButtonsBackground = background,
            )),
        ) {
            DecoratedWindow(
                title = "OpenRose",
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(width = 1180.dp, height = 820.dp),
            ) {
                TitleBar {
                    BasicText("OpenRose", style = TextStyle(color = LocalTitleBarStyle.current.colors.content))
                }
                OpenRoseApp(
                    state = state,
                    onSettingsChange = { settings ->
                        session.updateSettings(settings)
                        UserSettingsCodec.encode(settings).forEach { (key, value) -> preferences.put(key, value) }
                    },
                    onRequestPermission = {},
                    onOpenSystemSettings = {},
                    onStartScan = {},
                    onStopScan = {},
                    onConnect = {},
                    onDisconnect = {},
                    onSet = { _, _ -> },
                )
            }
        }
    }
}

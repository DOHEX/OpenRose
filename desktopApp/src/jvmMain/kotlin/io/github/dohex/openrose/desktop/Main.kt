package io.github.dohex.openrose.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.dohex.openrose.core.ControlSession
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.ui.OpenRoseApp
import java.util.prefs.Preferences

fun main() {
    val preferences = Preferences.userRoot().node("io/github/dohex/openrose")
    val platform = System.getProperty("os.name")
    // Created before composition: window/style changes cannot create another control owner.
    val session = ControlSession(
        initialSettings = UserSettingsCodec.decode(preferences.keys().associateWith { preferences.get(it, "") }),
        initialPlatformState = PlatformState(
            permission = PermissionState.NOT_REQUIRED,
            transport = TransportAvailability.Unavailable("$platform 控制传输尚未实现"),
            module = ModuleAvailability.Unavailable("HyperOS 系统集成仅适用于 Android"),
        ),
    )
    application {
        Window(
            title = "OpenRose",
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 920.dp, height = 760.dp),
        ) {
            val state by session.state.collectAsState()
            OpenRoseApp(
                state = state,
                onSettingsChange = { settings ->
                    session.updateSettings(settings)
                    UserSettingsCodec.encode(settings).forEach { (key, value) -> preferences.put(key, value) }
                },
                onRequestPermission = {},
                onOpenSystemSettings = {},
            )
        }
    }
}

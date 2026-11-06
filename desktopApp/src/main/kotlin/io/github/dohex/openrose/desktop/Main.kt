package io.github.dohex.openrose.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.transport.bluez.BluezControlTransport
import io.github.dohex.openrose.ui.OpenRoseApp
import io.github.dohex.openrose.ui.PlatformUi
import io.github.dohex.openrose.ui.interfaceBackgroundColor
import java.io.File
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    var owner: DesktopRuntime? = null
    try {
        nucleusApplication(args = args, backend = NucleusBackend.Tao, enableSingleInstance = true) {
            val runtime = remember {
                val platform = System.getProperty("os.name")
                val hostPlatform = when {
                    platform.contains("windows", ignoreCase = true) -> HostPlatform.WINDOWS
                    platform.contains("linux", ignoreCase = true) -> HostPlatform.LINUX
                    else -> HostPlatform.UNKNOWN
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val transport = if (hostPlatform == HostPlatform.LINUX) BluezControlTransport(scope) else null
                DesktopRuntime(
                    preferences = Preferences.userRoot().node("io/github/dohex/openrose"),
                    platform = PlatformState(
                        permission = PermissionState.NOT_REQUIRED,
                        transport = transport?.availability?.value
                            ?: TransportAvailability.Unavailable("$platform 控制传输尚未实现"),
                        hostPlatform = hostPlatform,
                        controlTransportApplicability = if (transport != null) PlatformApplicability.APPLICABLE
                            else PlatformApplicability.NOT_APPLICABLE,
                    ),
                    scope = scope,
                    transport = transport,
                ).also { owner = it }
            }
            val platformUi = remember { desktopPlatformUi(System.getProperty("os.name")) }
            val uiSettings by runtime.model.uiSettings.collectAsState()
            val dark = when (uiSettings.appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            val background = interfaceBackgroundColor(uiSettings.interfaceStyle, dark)
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
                    onCloseRequest = {
                        try {
                            runBlocking { runtime.close() }
                        } finally {
                            owner = null
                            exitApplication()
                        }
                    },
                    state = rememberWindowState(width = 1180.dp, height = 820.dp),
                ) {
                    TitleBar {
                        BasicText("OpenRose", style = TextStyle(color = LocalTitleBarStyle.current.colors.content))
                    }
                    OpenRoseApp(model = runtime.model, platformUi = platformUi)
                }
            }
        }
    } finally {
        owner?.let { runtime -> runBlocking { runtime.close() } }
    }
}

private fun desktopPlatformUi(platform: String): PlatformUi {
    val command = if (platform.contains("linux", ignoreCase = true)) {
        val directories = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        listOf(
            listOf("gnome-control-center", "bluetooth"),
            listOf("systemsettings", "kcm_bluetooth"),
            listOf("blueman-manager"),
        ).firstNotNullOfOrNull { candidate ->
            directories.firstNotNullOfOrNull { directory ->
                val executable = File(directory.ifEmpty { "." }, candidate.first())
                if (executable.isFile && executable.canExecute()) listOf(executable.path) + candidate.drop(1) else null
            }
        }
    } else null
    return object : PlatformUi {
        override val platformName = platform
        override val openBluetoothSettings: (() -> Unit)? = command?.let { args ->
            {
                runCatching {
                    ProcessBuilder(args)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }
                Unit
            }
        }
    }
}

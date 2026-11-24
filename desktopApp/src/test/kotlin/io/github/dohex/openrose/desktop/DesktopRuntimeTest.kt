package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.BottomBarStyle
import io.github.dohex.openrose.ui.InterfaceStyle
import io.github.dohex.openrose.ui.PageTransition
import io.github.dohex.openrose.ui.TrayPanelControl
import io.github.dohex.openrose.ui.TrayPanelSettings
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.desktop.DesktopSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRuntimeTest {
    @Test
    fun controlAndAppearanceSurviveRebuildingTheRuntime() = runTest {
        val root = Files.createTempDirectory("openrose-desktop-test").toFile()
        val paths = DesktopPaths(File(root, "config/settings.properties"), File(root, "data/circulation"))
        try {
            val first = DesktopRuntime(paths, platform, backgroundScope)
            val chosenControl = UserSettings(forceBle = true)
            val chosenUi = UiSettings(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.DARK,
                pageTransition = PageTransition.NONE,
                backgroundBlurEnabled = false,
                bottomBarStyle = BottomBarStyle.FLOATING,
            )
            first.model.updateSettings(chosenControl)
            first.updateDesktopSettings(DesktopSettings(backgroundControl = false))
            val observed = first.model.state.value
            first.model.updateUiSettings(chosenUi)
            assertEquals(observed, first.model.state.value)

            val rebuilt = DesktopRuntime(paths, platform, backgroundScope)
            assertEquals(chosenControl, rebuilt.model.state.value.settings)
            assertEquals(chosenUi, rebuilt.model.uiSettings.value)
            assertEquals(DesktopSettings(backgroundControl = false), rebuilt.desktopSettings.value)
            assertEquals("false", DesktopSettingsFile(paths.settingsFile).load()["desktop.background_control"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun closingTheDesktopWaitsForItsBackgroundServicesToReleaseTheirResources() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("openrose-desktop-test").toFile()
        val paths = DesktopPaths(File(root, "settings.properties"), File(root, "circulation"))
        val released = CompletableDeferred<Unit>()
        try {
            val runtime = DesktopRuntime(paths, platform, scope)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(100)
                        released.complete(Unit)
                    }
                }
            }
            runtime.close()
            assertTrue(released.isCompleted)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun desktopPathsFollowPlatformConventions() {
        val home = File(System.getProperty("java.io.tmpdir"), "openrose-home").absoluteFile
        val customConfig = File(home, "custom-config")
        val customData = File(home, "custom-data")
        val linuxDefault = desktopPaths(HostPlatform.LINUX, home = home, env = { null })
        assertEquals(File(home, ".config/openrose/settings.properties"), linuxDefault.settingsFile)
        assertEquals(File(home, ".local/share/openrose/circulation"), linuxDefault.circulationDirectory)

        val linuxXdg = desktopPaths(
            HostPlatform.LINUX,
            home = home,
            env = mapOf("XDG_CONFIG_HOME" to customConfig.path, "XDG_DATA_HOME" to customData.path)::get,
        )
        assertEquals(File(customConfig, "openrose/settings.properties"), linuxXdg.settingsFile)
        assertEquals(File(customData, "openrose/circulation"), linuxXdg.circulationDirectory)

        val linuxRelativeXdg = desktopPaths(
            HostPlatform.LINUX,
            home = home,
            env = mapOf("XDG_CONFIG_HOME" to "relative/config", "XDG_DATA_HOME" to "relative/data")::get,
        )
        assertEquals(File(home, ".config/openrose/settings.properties"), linuxRelativeXdg.settingsFile)
        assertEquals(File(home, ".local/share/openrose/circulation"), linuxRelativeXdg.circulationDirectory)

        val windowsEnv = desktopPaths(
            HostPlatform.WINDOWS,
            home = home,
            env = mapOf("APPDATA" to customConfig.path, "LOCALAPPDATA" to customData.path)::get,
        )
        assertEquals(File(customConfig, "OpenRose/settings.properties"), windowsEnv.settingsFile)
        assertEquals(File(customData, "OpenRose/circulation"), windowsEnv.circulationDirectory)

        val windowsFallback = desktopPaths(HostPlatform.WINDOWS, home = home, env = { null })
        assertEquals(File(home, "AppData/Roaming/OpenRose/settings.properties"), windowsFallback.settingsFile)
        assertEquals(File(home, "AppData/Local/OpenRose/circulation"), windowsFallback.circulationDirectory)
    }

    @Test
    fun missingOrUnknownDesktopValuesKeepBackgroundControlOn() {
        assertEquals(DesktopSettings(backgroundControl = true), DesktopSettingsCodec.decode(emptyMap()))
        assertEquals(DesktopSettings(backgroundControl = true), DesktopSettingsCodec.decode(mapOf("desktop.background_control" to "yes")))
    }

    @Test
    fun trayPanelSettingsEncodeDecodeAndNormalize() {
        val custom = DesktopSettings(
            backgroundControl = true,
            trayPanel = TrayPanelSettings(
                showNoiseLevels = false,
                enabledControls = setOf(TrayPanelControl.SPATIAL_AUDIO, TrayPanelControl.GAME_MODE),
                controlOrder = listOf(
                    TrayPanelControl.SPATIAL_AUDIO,
                    TrayPanelControl.INDEPENDENT_MIC,
                    TrayPanelControl.GAME_MODE,
                    TrayPanelControl.EQUALIZER,
                ),
            ),
        )
        val encoded = DesktopSettingsCodec.encode(custom)
        assertEquals(custom, DesktopSettingsCodec.decode(encoded))

        val emptyEnabled = DesktopSettingsCodec.decode(
            mapOf(
                "desktop.tray_panel.noise_levels" to "false",
                "desktop.tray_panel.enabled" to "",
                "desktop.tray_panel.order" to "equalizer,unknown",
            ),
        )
        assertEquals(false, emptyEnabled.trayPanel.showNoiseLevels)
        assertEquals(emptySet(), emptyEnabled.trayPanel.enabledControls)
        assertEquals(
            listOf(
                TrayPanelControl.EQUALIZER,
                TrayPanelControl.GAME_MODE,
                TrayPanelControl.SPATIAL_AUDIO,
                TrayPanelControl.INDEPENDENT_MIC,
            ),
            emptyEnabled.trayPanel.controlOrder,
        )
    }

    private val platform = PlatformState(permission = PermissionState.NOT_REQUIRED, hostPlatform = HostPlatform.LINUX)
}

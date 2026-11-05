package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.ui.Appearance
import io.github.dohex.openrose.ui.BottomBarStyle
import io.github.dohex.openrose.ui.InterfaceStyle
import io.github.dohex.openrose.ui.PageTransition
import io.github.dohex.openrose.ui.UiSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DesktopRuntimeTest {
    @Test
    fun controlAndAppearanceSurviveRebuildingTheRuntime() = runTest {
        val path = "io/github/dohex/openrose/test/${UUID.randomUUID()}"
        val preferences = Preferences.userRoot().node(path)
        try {
            val first = DesktopRuntime(preferences, platform, backgroundScope)
            val chosenControl = UserSettings(forceBle = true)
            val chosenUi = UiSettings(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.DARK,
                pageTransition = PageTransition.NONE,
                backgroundBlurEnabled = false,
                bottomBarStyle = BottomBarStyle.FLOATING,
            )
            first.updateSettings(chosenControl)
            val observed = first.control.state.value
            first.updateUiSettings(chosenUi)
            assertEquals(observed, first.control.state.value)

            val rebuilt = DesktopRuntime(Preferences.userRoot().node(path), platform, backgroundScope)
            assertEquals(chosenControl, rebuilt.control.state.value.settings)
            assertEquals(chosenUi, rebuilt.uiSettings.value)
            assertEquals("true", preferences.get("core.force_ble", null))
            assertEquals("material3", preferences.get("sharedUI.interface_style", null))
        } finally {
            preferences.removeNode()
            Preferences.userRoot().flush()
        }
    }

    @Test
    fun oldKeysDoNotRestoreObsoletePreferences() = runTest {
        val preferences = Preferences.userRoot().node("io/github/dohex/openrose/test/${UUID.randomUUID()}")
        try {
            preferences.put("force_ble", "true")
            preferences.put("interface_style", "material3")
            preferences.put("appearance", "dark")
            val runtime = DesktopRuntime(preferences, platform, backgroundScope)
            assertEquals(UserSettings(), runtime.control.state.value.settings)
            assertEquals(UiSettings(), runtime.uiSettings.value)
        } finally {
            preferences.removeNode()
            Preferences.userRoot().flush()
        }
    }

    private val platform = PlatformState(permission = PermissionState.NOT_REQUIRED, hostPlatform = HostPlatform.LINUX)
}

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundBlurUiTest {

    @Test
    fun backgroundBlurSettingRowRendersAndToggles() = runBlocking {
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(PermissionState.GRANTED),
                settings = UserSettings(
                    interfaceStyle = InterfaceStyle.MIUIX,
                    appearance = Appearance.LIGHT,
                    backgroundBlurEnabled = true,
                ),
            ),
        )
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { state.value = state.value.copy(settings = it) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            harness.clickTab("设置")
            harness.render()

            assertTrue(harness.hasText("背景模糊"), "Settings page must render Background Blur preference")
            assertTrue(harness.hasText("顶栏、底栏及浮层启用毛玻璃透视效果"), "Settings page must describe Background Blur behavior")
            assertTrue(state.value.settings.backgroundBlurEnabled, "Background blur should initially be enabled")

            harness.clickText("背景模糊")
            harness.render()

            assertFalse(state.value.settings.backgroundBlurEnabled, "Toggling background blur should disable it in settings")

            harness.clickText("背景模糊")
            harness.render()

            assertTrue(state.value.settings.backgroundBlurEnabled, "Toggling background blur again should re-enable it in settings")
        } finally {
            harness.close()
        }
    }

    @Test
    fun backgroundBlurSettingWorksInMaterialStyle() = runBlocking {
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(PermissionState.GRANTED),
                settings = UserSettings(
                    interfaceStyle = InterfaceStyle.MATERIAL3,
                    appearance = Appearance.LIGHT,
                    backgroundBlurEnabled = true,
                ),
            ),
        )
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { state.value = state.value.copy(settings = it) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            harness.clickTab("设置")
            harness.render()

            assertTrue(harness.hasText("背景模糊"), "Material 3 settings page must render Background Blur preference")

            harness.clickText("背景模糊")
            harness.render()

            assertFalse(state.value.settings.backgroundBlurEnabled, "Toggling background blur should disable it in Material 3")
        } finally {
            harness.close()
        }
    }
}

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.BottomBarStyle
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BottomBarStyleUiTest {

    @Test
    fun bottomBarStyleChoiceRowRendersUnderDisplayModeAndUpdatesSettings() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val settingsChanges = mutableListOf<UserSettings>()
            val state = mutableStateOf(
                ControlState(
                    platform = PlatformState(permission = PermissionState.GRANTED),
                    settings = UserSettings(
                        interfaceStyle = style,
                        bottomBarStyle = BottomBarStyle.FIXED,
                    ),
                ),
            )
            val scene = createOpenRoseUiTestScene(420, 900) {
                OpenRoseApp(
                    state = state.value,
                    onSettingsChange = { next ->
                        settingsChanges += next
                        state.value = state.value.copy(settings = next)
                    },
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

                assertTrue(harness.hasText("显示模式"), "Settings page must render display mode section for $style")
                assertTrue(harness.hasText("底栏样式"), "Settings page must render bottom bar style choice row for $style")
                assertTrue(harness.hasText("标准固定"), "Default bottom bar style label must be visible for $style")
                assertEquals(BottomBarStyle.FIXED, state.value.settings.bottomBarStyle)

                harness.clickText("底栏样式")
                harness.render()

                assertTrue(harness.hasText("标准固定"), "标准固定 option must appear in picker for $style")
                assertTrue(harness.hasText("悬浮胶囊"), "悬浮胶囊 option must appear in picker for $style")

                harness.clickText("悬浮胶囊")
                harness.render()

                assertEquals(BottomBarStyle.FLOATING, state.value.settings.bottomBarStyle)
                assertEquals(BottomBarStyle.FLOATING, settingsChanges.last().bottomBarStyle)
                assertTrue(harness.hasText("悬浮胶囊"), "Selected floating style label must be visible for $style")

                harness.clickText("底栏样式")
                harness.render()
                harness.clickText("标准固定")
                harness.render()

                assertEquals(BottomBarStyle.FIXED, state.value.settings.bottomBarStyle)
                assertEquals(BottomBarStyle.FIXED, settingsChanges.last().bottomBarStyle)
                assertTrue(harness.hasText("标准固定"), "Selected fixed style label must be visible for $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun bottomBarStylePreferenceIsRetainedAcrossInterfaceStyleSwitches() = runBlocking {
        val settingsChanges = mutableListOf<UserSettings>()
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(permission = PermissionState.GRANTED),
                settings = UserSettings(
                    interfaceStyle = InterfaceStyle.MIUIX,
                    bottomBarStyle = BottomBarStyle.FLOATING,
                ),
            ),
        )
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { next ->
                    settingsChanges += next
                    state.value = state.value.copy(settings = next)
                },
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

            assertTrue(harness.hasText("底栏样式"))
            assertTrue(harness.hasText("悬浮胶囊"))
            assertEquals(BottomBarStyle.FLOATING, state.value.settings.bottomBarStyle)

            // Switch to Material 3
            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Material 3 Expressive")
            harness.render()

            assertEquals(InterfaceStyle.MATERIAL3, state.value.settings.interfaceStyle)
            assertEquals(BottomBarStyle.FLOATING, state.value.settings.bottomBarStyle)
            assertTrue(harness.hasText("底栏样式"))
            assertTrue(harness.hasText("悬浮胶囊"))

            // Switch back to Miuix
            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Miuix")
            harness.render()

            assertEquals(InterfaceStyle.MIUIX, state.value.settings.interfaceStyle)
            assertEquals(BottomBarStyle.FLOATING, state.value.settings.bottomBarStyle)
            assertTrue(harness.hasText("底栏样式"))
            assertTrue(harness.hasText("悬浮胶囊"))
        } finally {
            harness.close()
        }
    }
}

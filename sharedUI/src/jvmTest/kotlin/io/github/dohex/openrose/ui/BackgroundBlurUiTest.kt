@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundBlurUiTest {

    @Test
    fun backgroundBlurSettingRowRendersAndToggles() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(
                ControlState(
                    platform = PlatformState(PermissionState.GRANTED),
                    settings = UserSettings(
                        interfaceStyle = style,
                        appearance = Appearance.LIGHT,
                        backgroundBlurEnabled = true,
                    ),
                ),
            ) { harness ->
                harness.render()
                harness.clickTab("设置")
                harness.render()

                assertTrue(harness.hasText("背景模糊"), "Settings page must render Background Blur preference in $style")
                assertTrue(harness.hasText("顶栏、底栏及浮层启用毛玻璃透视效果"), "Settings page must describe Background Blur in $style")

                harness.clickText("背景模糊")
                harness.render()
                assertFalse(harness.state.value.settings.backgroundBlurEnabled, "Toggling must disable background blur in $style")

                harness.clickText("背景模糊")
                harness.render()
                assertTrue(harness.state.value.settings.backgroundBlurEnabled, "Toggling again must re-enable background blur in $style")
            }
        }
    }
}

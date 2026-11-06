@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import io.github.dohex.openrose.core.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundBlurUiTest {

    @Test
    fun backgroundBlurSettingRowRendersAndToggles() {
        for (style in InterfaceStyle.entries) {
            withPersonalizationHarness(
                ControlState(
                    platform = PlatformState(PermissionState.GRANTED),
                ),
                initialUiSettings = UiSettings(
                    interfaceStyle = style,
                    appearance = Appearance.LIGHT,
                    backgroundBlurEnabled = true,
                ),
            ) { harness ->
                harness.render()

                assertTrue(harness.hasText("背景模糊"), "Personalization page must render Background Blur preference in $style")
                assertTrue(harness.hasText("顶栏、底栏及浮层启用毛玻璃透视效果"), "Personalization page must describe Background Blur in $style")

                harness.clickText("背景模糊")
                harness.render()
                assertFalse(harness.uiSettings.value.backgroundBlurEnabled, "Toggling must disable background blur in $style")

                harness.clickText("背景模糊")
                harness.render()
                assertTrue(harness.uiSettings.value.backgroundBlurEnabled, "Toggling again must re-enable background blur in $style")
            }
        }
    }
}

internal fun withPersonalizationHarness(
    initialState: ControlState,
    initialUiSettings: UiSettings = UiSettings(),
    block: suspend (OpenRoseAppHarness) -> Unit,
) = runOpenRoseUiTest(420, 900) {
    val state = mutableStateOf(initialState)
    val uiSettings = mutableStateOf(initialUiSettings)
    val model = FakeOpenRoseModel(state, uiSettings)
    setContent {
        TestPageContent(uiSettings.value.interfaceStyle, dark = uiSettings.value.appearance == Appearance.DARK) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PersonalizationPage(model)
            }
        }
    }
    block(OpenRoseAppHarness(this, state, uiSettings, model))
}

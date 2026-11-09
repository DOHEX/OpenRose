package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicColorUiTest {
    @Test
    fun dynamicColorIsOfferedOnlyWhereTheSystemProvidesWallpaperColors() {
        for (style in InterfaceStyle.entries) {
            for (applicability in PlatformApplicability.entries) {
                withHarness(style, applicability) { harness ->
                    harness.render()
                    val applicable = applicability == PlatformApplicability.APPLICABLE
                    assertEquals(applicable, harness.hasText("动态取色"), "$applicability wallpaper colors in $style")
                    if (!applicable) return@withHarness

                    harness.clickText("动态取色")
                    harness.render()
                    assertTrue(harness.integration.value.settings.dynamicColor, "switching on saves 动态取色 in $style")
                    harness.clickText("动态取色")
                    harness.render()
                    assertFalse(harness.integration.value.settings.dynamicColor, "switching off saves 动态取色 in $style")
                }
            }
        }
    }

    private fun withHarness(
        style: InterfaceStyle,
        applicability: PlatformApplicability,
        block: suspend (Harness) -> Unit,
    ) = runOpenRoseUiTest(420, 900) {
        val integration = MutableStateFlow(
            IntegrationState(platform = IntegrationPlatformState(dynamicColorApplicability = applicability)),
        )
        val page = mutableStateOf("个性化")
        val control = ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
            ),
        )
        val model = FakeOpenRoseModel(mutableStateOf(control), mutableStateOf(UiSettings(interfaceStyle = style)))
        setContent { AndroidTestPage(model, androidTestPlatform(integration), page) }
        block(Harness(this, page, integration))
    }

    private class Harness(
        scene: OpenRoseUiTestScene,
        page: MutableState<String>,
        val integration: MutableStateFlow<IntegrationState>,
    ) : AndroidPageHarness(scene, page)
}

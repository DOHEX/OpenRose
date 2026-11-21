package io.github.dohex.openrose.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AboutPageUiTest {

    @Test
    fun settingsOpensAboutWhoseLinksOpenTheBrowserAndAMissingBrowserLeavesThePageIntact() {
        for (style in InterfaceStyle.entries) {
            for (width in listOf(420, 1200)) {
                val case = "$style width=$width"
                val opened = mutableListOf<String>()
                // Every request fails, as on a desktop without a browser; the page must stay usable.
                val browserless = object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                        throw UnsupportedOperationException("no browser")
                    }
                }
                runOpenRoseUiTest(width, 1000) {
                    val model = FakeOpenRoseModel(mutableUiSettings = mutableStateOf(UiSettings(interfaceStyle = style)))
                    setContent {
                        CompositionLocalProvider(LocalUriHandler provides browserless) {
                            OpenRoseApp(model = model, platformUi = BasicPlatformUi("Test"))
                        }
                    }
                    val harness = OpenRoseUiTestHarness(this)
                    harness.render()
                    harness.clickTab("设置")
                    harness.render()
                    assertTrue(harness.hasText("版本、项目链接与致谢"), "Settings shows the 关于 entry in $case")
                    assertFalse(harness.hasText("致谢"), "Acknowledgements are not on Settings in $case")

                    harness.clickText("关于")
                    harness.render()
                    assertTrue(harness.hasText("版本 ${AppVersion.NAME}"), "关于 shows the build's version in $case")
                    assertTrue(harness.hasText("致谢"), "关于 lists the acknowledgements in $case")

                    harness.clickText("Miuix")
                    harness.render()
                    assertEquals(listOf("https://github.com/compose-miuix-ui/miuix"), opened, "Miuix opens its repository in $case")
                    assertTrue(harness.hasText("致谢"), "A failed browser launch keeps 关于 open in $case")

                    harness.clickContentDescription("返回")
                    harness.render(frames = 120)
                    assertTrue(harness.hasText("版本、项目链接与致谢"), "Back returns to Settings in $case")
                    assertFalse(harness.hasText("致谢"), "Back leaves 关于 in $case")
                }
            }
        }
    }
}

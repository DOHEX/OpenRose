package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoticeBannerUiTest {
    @Test
    fun aHostNoticeShowsAtTheTopOfTheHeadphonesPageUntilItIsDismissed() {
        for (style in InterfaceStyle.entries) {
            val model = FakeOpenRoseModel(mutableUiSettings = mutableStateOf(UiSettings(interfaceStyle = style)))
            runOpenRoseUiTest(420, 900) {
                setContent { OpenRoseApp(model, BasicPlatformUi("Test")) }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                assertFalse(harness.hasText(NOTICE), "no notice before the host posts one in $style")

                model.notice.value = NOTICE
                harness.render()
                assertTrue(harness.hasText(NOTICE), "the notice is shown in $style")

                harness.clickText("知道了")
                harness.render()
                assertFalse(harness.hasText(NOTICE), "dismissing hides the notice in $style")
                assertNull(model.notice.value)
            }
        }
    }

    private companion object {
        const val NOTICE = "RoseLink 未安装，已打开 OpenRose"
    }
}

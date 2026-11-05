@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageTransitionUiTest {
    @Test
    fun pageTransitionChoiceRowAppearsAndDispatchesChanges() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(
                ControlState(
                    platform = PlatformState(permission = PermissionState.GRANTED),
                ),
                initialUiSettings = UiSettings(interfaceStyle = style, pageTransition = PageTransition.MIUIX_SPRING),
            ) { harness ->
                harness.render()
                harness.clickTab("设置")
                harness.render()

                assertTrue(harness.hasText("转场动效"), "transition choice row must appear in Settings for $style")
                assertTrue(harness.hasText("Miuix 弹性滑动"), "default transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()

                assertTrue(harness.hasText("标准平移"), "SLIDE option must appear in picker for $style")
                assertTrue(harness.hasText("淡入淡出"), "FADE option must appear in picker for $style")
                assertTrue(harness.hasText("关闭动效"), "NONE option must appear in picker for $style")

                harness.clickText("淡入淡出")
                harness.render()
                assertEquals(PageTransition.FADE, harness.settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("淡入淡出"), "selected transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()
                harness.clickText("关闭动效")
                harness.render()
                assertEquals(PageTransition.NONE, harness.settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("关闭动效"), "selected transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()
                harness.clickText("标准平移")
                harness.render()
                assertEquals(PageTransition.SLIDE, harness.settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("标准平移"), "selected transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()
                harness.clickText("Miuix 弹性滑动")
                harness.render()
                assertEquals(PageTransition.MIUIX_SPRING, harness.settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("Miuix 弹性滑动"), "selected transition label must be visible for $style")
            }
        }
    }
}

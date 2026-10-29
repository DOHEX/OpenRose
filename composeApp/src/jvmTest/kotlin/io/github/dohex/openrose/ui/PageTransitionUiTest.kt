@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.PageTransition
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageTransitionUiTest {
    @Test
    fun pageTransitionChoiceRowAppearsAndDispatchesChanges() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val settingsChanges = mutableListOf<UserSettings>()
            val state = mutableStateOf(
                ControlState(
                    platform = PlatformState(permission = PermissionState.GRANTED),
                    settings = UserSettings(
                        interfaceStyle = style,
                        pageTransition = PageTransition.MIUIX_SPRING,
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
                )
            }
            val harness = OpenRoseUiTestHarness(scene)
            try {
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

                assertEquals(PageTransition.FADE, state.value.settings.pageTransition)
                assertEquals(PageTransition.FADE, settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("淡入淡出"), "selected transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()
                harness.clickText("关闭动效")
                harness.render()

                assertEquals(PageTransition.NONE, state.value.settings.pageTransition)
                assertEquals(PageTransition.NONE, settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("关闭动效"), "selected transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()
                harness.clickText("标准平移")
                harness.render()

                assertEquals(PageTransition.SLIDE, state.value.settings.pageTransition)
                assertEquals(PageTransition.SLIDE, settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("标准平移"), "selected transition label must be visible for $style")

                harness.clickText("转场动效")
                harness.render()
                harness.clickText("Miuix 弹性滑动")
                harness.render()

                assertEquals(PageTransition.MIUIX_SPRING, state.value.settings.pageTransition)
                assertEquals(PageTransition.MIUIX_SPRING, settingsChanges.last().pageTransition)
                assertTrue(harness.hasText("Miuix 弹性滑动"), "selected transition label must be visible for $style")
            } finally {
                harness.close()
            }
        }
    }
}

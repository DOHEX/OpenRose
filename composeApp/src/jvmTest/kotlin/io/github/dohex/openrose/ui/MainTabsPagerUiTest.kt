@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.input.key.Key
import io.github.dohex.openrose.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsPagerUiTest {

    @Test
    fun horizontalPagerNavigatesThroughAllThreeMainTabsUnderBothStyles() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createOpenRoseAppHarness(connectedI7State(UserSettings(interfaceStyle = style)))
            try {
                harness.render()
                assertTrue(harness.hasText("均衡器"), "Initial tab is Headphones in $style")
                assertFalse(harness.hasText("组件风格"), "Settings must not be active on startup in $style")

                // Navigate to Status tab
                harness.clickTab("状态")
                harness.render()
                assertTrue(harness.hasText("运行状态与诊断"), "Status active in $style")
                assertFalse(harness.hasText("均衡器"), "Headphones content quarantined in $style")

                // Navigate to Settings tab
                harness.clickTab("设置")
                harness.render()
                assertTrue(harness.hasText("组件风格"), "Settings tab active in $style")
                assertFalse(harness.hasText("运行状态与诊断"), "System content quarantined in $style")

                // Back key returns to Headphones home
                harness.sendKey(Key.Escape)
                harness.render()
                assertTrue(harness.hasText("均衡器"), "Pressing back from Settings returns to Headphones in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun nonePageTransitionJumpsImmediatelyWithoutAnimationJitter() = runBlocking {
        val harness = createOpenRoseAppHarness(connectedI7State(UserSettings(pageTransition = PageTransition.NONE)))
        try {
            harness.render()
            assertTrue(harness.hasText("均衡器"))

            harness.clickTab("设置")
            harness.render(frames = 2)
            assertTrue(harness.hasText("组件风格"), "Instant jump to Settings when pageTransition is NONE")
        } finally {
            harness.close()
        }
    }
}

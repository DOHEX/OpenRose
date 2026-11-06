@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import io.github.dohex.openrose.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsPagerUiTest {

    @Test
    fun horizontalPagerNavigatesThroughAllThreeMainTabsUnderBothStyles() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(connectedI7State(), initialUiSettings = UiSettings(interfaceStyle = style)) { harness ->
                harness.render()
                assertTrue(harness.hasText("均衡器"), "Initial tab is Headphones in $style")
                assertFalse(harness.hasText("个性化"), "Settings must not be active on startup in $style")

                // Navigate to Status tab
                harness.clickTab("状态")
                harness.render()
                assertTrue(harness.hasText("运行状态与诊断"), "Status active in $style")
                assertFalse(harness.hasText("均衡器"), "Headphones content quarantined in $style")

                // Navigate to Settings tab
                harness.clickTab("设置")
                harness.render()
                assertTrue(harness.hasText("个性化"), "Settings tab active in $style")
                assertFalse(harness.hasText("运行状态与诊断"), "System content quarantined in $style")

                // Back key returns to Headphones home
                harness.sendKey(Key.Escape)
                harness.render()
                assertTrue(harness.hasText("均衡器"), "Pressing back from Settings returns to Headphones in $style")
            }
        }
    }

    @Test
    fun nonePageTransitionJumpsImmediatelyWithoutAnimationJitter() {
        withOpenRoseAppHarness(connectedI7State(), initialUiSettings = UiSettings(pageTransition = PageTransition.NONE)) { harness ->
            harness.render()
            assertTrue(harness.hasText("均衡器"))

            harness.clickTab("设置")
            harness.render(frames = 2)
            assertTrue(harness.hasText("个性化"), "Instant jump to Settings when pageTransition is NONE")
        }
    }

    @Test
    fun mainPagesTravelAlongTheNavigationAxisRegardlessOfTheTransitionPreference() {
        val animated = listOf(PageTransition.MIUIX_SPRING, PageTransition.SLIDE, PageTransition.FADE)
        for (style in InterfaceStyle.entries) {
            for (wide in listOf(false, true)) {
                val midway = animated.map { transition ->
                    var moving = Rect.Zero
                    withOpenRoseAppHarness(
                        connectedI7State(),
                        initialUiSettings = UiSettings(interfaceStyle = style, pageTransition = transition),
                        width = if (wide) 1200 else 420,
                    ) { harness ->
                        harness.render()
                        harness.clickTab("状态")
                        harness.render(frames = 3)
                        moving = harness.textBounds("运行状态与诊断")
                        harness.render(frames = 120)
                        val settled = harness.textBounds("运行状态与诊断")
                        val case = "$style wide=$wide $transition"
                        if (wide) {
                            assertEquals(settled.left, moving.left, "wide pages must not move sideways in $case")
                            assertTrue(moving.top > settled.top + 1f, "wide Status must rise from below the rail order in $case")
                        } else {
                            assertEquals(settled.top, moving.top, "narrow pages must not move vertically in $case")
                            assertTrue(moving.left > settled.left + 1f, "narrow Status must enter from the right in $case")
                        }
                    }
                    moving
                }
                assertEquals(1, midway.distinct().size, "main page motion must not follow the transition preference ($style wide=$wide): $midway")
            }
        }
    }
}

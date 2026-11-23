@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseNavDisplayTest {

    @Test
    fun secondaryRoutesPushFullScreenAndHideBottomBar() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(connectedI7State(), initialUiSettings = UiSettings(interfaceStyle = style)) { harness ->
                harness.render()
                // On root main tab: bottom navigation bar tabs exist
                assertTrue(harness.hasText("状态"), "Main tab should have 状态 navigation tab in $style")
                assertTrue(harness.hasText("设置"), "Main tab should have 设置 navigation tab in $style")

                // Push a remaining secondary route.
                harness.clickText("触控操作")
                harness.render()

                // Inside secondary route: title and back button exist, bottom navigation tabs are HIDDEN
                assertTrue(harness.hasText("触控操作"))
                assertTrue(harness.hasContentDescription("返回"))
                assertFalse(harness.hasText("状态"), "Bottom navigation bar must be hidden in secondary route in $style")
                assertFalse(harness.hasText("设置"), "Bottom navigation bar must be hidden in secondary route in $style")

                // Click back button: pops secondary route, restores root main tab with bottom bar
                harness.clickContentDescription("返回")
                harness.render()

                assertTrue(harness.hasText("均衡器"), "Home card should be visible")
                assertTrue(harness.hasText("状态"), "Bottom navigation bar must be restored on returning to main in $style")
                assertTrue(harness.hasText("设置"), "Bottom navigation bar must be restored on returning to main in $style")
            }
        }
    }
}

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.semantics.Role
import io.github.dohex.openrose.core.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseNavDisplayTest {

    @Test
    fun secondaryRoutesPushFullScreenAndHideBottomBar() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(connectedI7State(UserSettings(interfaceStyle = style))) { harness ->
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

    @Test
    fun interfaceStyleSwitchPreservesNavigationStack() {
        withOpenRoseAppHarness(connectedI7State()) { harness ->
            harness.render()
            harness.clickText("触控操作")
            harness.render()
            assertTrue(harness.hasText("触控操作"))

            // Switch interface style while on secondary route
            harness.state.value = harness.state.value.copy(
                settings = harness.state.value.settings.copy(interfaceStyle = InterfaceStyle.MATERIAL3),
            )
            harness.render()

            // Still on secondary route
            assertTrue(harness.hasText("触控操作"))
            assertTrue(harness.hasContentDescription("返回"))

            // Can still navigate back in Material 3
            harness.clickContentDescription("返回")
            harness.render()
            assertTrue(harness.hasRole(Role.Tab))
        }
    }

    @Test
    fun pageTransitionPreferencesApplyCleanly() {
        for (transition in PageTransition.entries) {
            withOpenRoseAppHarness(connectedI7State(UserSettings(pageTransition = transition))) { harness ->
                harness.render()
                harness.clickText("触控操作")
                harness.render()
                assertTrue(harness.hasText("触控操作"), "Navigation should work for transition $transition")
                harness.clickContentDescription("返回")
                harness.render()
                assertTrue(harness.hasText("均衡器"))
            }
        }
    }
}

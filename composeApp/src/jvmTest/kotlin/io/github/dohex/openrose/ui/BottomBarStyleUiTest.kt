@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BottomBarStyleUiTest {

    @Test
    fun bottomBarStyleChoiceRowRendersUnderDisplayModeAndUpdatesSettings() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createOpenRoseAppHarness(
                ControlState(
                    platform = PlatformState(permission = PermissionState.GRANTED),
                    settings = UserSettings(interfaceStyle = style, bottomBarStyle = BottomBarStyle.FIXED),
                ),
            )
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()

                assertTrue(harness.hasText("显示模式"), "Settings page must render display mode section for $style")
                assertTrue(harness.hasText("底栏样式"), "Settings page must render bottom bar style choice row for $style")
                assertTrue(harness.hasText("标准固定"), "Default bottom bar style label must be visible for $style")

                harness.clickText("底栏样式")
                harness.render()
                assertTrue(harness.hasText("悬浮胶囊"), "悬浮胶囊 option must appear in picker for $style")
                harness.clickText("悬浮胶囊")
                harness.render()
                assertEquals(BottomBarStyle.FLOATING, harness.settingsChanges.last().bottomBarStyle)
                assertTrue(harness.hasText("悬浮胶囊"), "Selected floating style label must be visible for $style")

                harness.clickText("底栏样式")
                harness.render()
                harness.clickText("标准固定")
                harness.render()
                assertEquals(BottomBarStyle.FIXED, harness.settingsChanges.last().bottomBarStyle)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun bottomBarStylePreferenceIsRetainedAcrossInterfaceStyleSwitches() = runBlocking {
        val harness = createOpenRoseAppHarness(
            ControlState(
                platform = PlatformState(permission = PermissionState.GRANTED),
                settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FLOATING),
            ),
        )
        try {
            harness.render()
            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("悬浮胶囊"))

            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Material 3 Expressive")
            harness.render()
            assertEquals(InterfaceStyle.MATERIAL3, harness.state.value.settings.interfaceStyle)
            assertEquals(BottomBarStyle.FLOATING, harness.state.value.settings.bottomBarStyle)
            assertTrue(harness.hasText("悬浮胶囊"))
            assertTrue(harness.hasText("组件风格"), "Settings page must remain active after style switch")

            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Miuix")
            harness.render()
            assertEquals(InterfaceStyle.MIUIX, harness.state.value.settings.interfaceStyle)
            assertEquals(BottomBarStyle.FLOATING, harness.state.value.settings.bottomBarStyle)
            assertTrue(harness.hasText("悬浮胶囊"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun fixedBottomBarRendersLabelledTabsAndNavigates() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createOpenRoseAppHarness(connectedI7State(settings(style, BottomBarStyle.FIXED)))
            try {
                harness.render()
                val tabs = harness.tabs()
                assertEquals(3, tabs.size, "Must have 3 bottom bar tabs in $style")
                for (label in listOf("耳机", "系统", "设置")) {
                    assertTrue(tabs.any { it.hasText(label) }, "Fixed tab '$label' must show its label in $style")
                }

                harness.clickTab("设置")
                harness.render()
                assertTrue(harness.hasText("组件风格"), "Settings page active after clicking 设置 tab in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun floatingBottomBarRendersIconTabsAndNavigates() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createOpenRoseAppHarness(connectedI7State(settings(style, BottomBarStyle.FLOATING)))
            try {
                harness.render()
                val tabs = harness.tabs()
                assertEquals(3, tabs.size, "Must have 3 floating bottom bar tabs in $style")
                assertTrue(tabs.none { it.hasText("耳机") }, "Floating tab bar must not render text labels in $style")
                assertEquals(true, tabs.tabFor("耳机").config.getOrNull(SemanticsProperties.Selected))
                assertEquals(false, tabs.tabFor("设置").config.getOrNull(SemanticsProperties.Selected))

                harness.clickContentDescription("设置")
                harness.render()
                assertTrue(harness.hasText("组件风格"), "Settings page active after clicking 设置 floating tab in $style")
                val updated = harness.tabs()
                assertEquals(false, updated.tabFor("耳机").config.getOrNull(SemanticsProperties.Selected))
                assertEquals(true, updated.tabFor("设置").config.getOrNull(SemanticsProperties.Selected))
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun onlyFloatingBottomBarReservesTrailingClearance() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val floating = createOpenRoseAppHarness(connectedI7State(settings(style, BottomBarStyle.FLOATING)))
            try {
                floating.render()
                val clearance = assertNotNull(floating.clearance(), "Trailing clearance must be rendered in $style floating mode")
                val bar = floating.nodes().first { node -> node.children.any { it.role() == Role.Tab } }
                assertTrue(
                    clearance.size.height >= bar.size.height,
                    "Clearance (${clearance.size.height}) must be >= floating bar height (${bar.size.height}) in $style",
                )
            } finally {
                floating.close()
            }

            val fixed = createOpenRoseAppHarness(connectedI7State(settings(style, BottomBarStyle.FIXED)))
            try {
                fixed.render()
                assertNull(fixed.clearance(), "Fixed bottom bar must not add trailing clearance in $style")
            } finally {
                fixed.close()
            }
        }
    }

    @Test
    fun feedbackOverlaySitsAboveTheFloatingBottomBar() = runBlocking {
        val operation = SettingOperation(
            id = 100,
            sessionId = 1L,
            setting = DeviceSetting.NOISE_MODE,
            requestedValue = FieldValue.Text("anc"),
            stage = SettingStage.SUBMITTED,
        )
        for (style in InterfaceStyle.entries) {
            val harness = createOpenRoseAppHarness(
                connectedI7State(settings(style, BottomBarStyle.FLOATING)).copy(settingOperations = listOf(operation)),
            )
            try {
                harness.render()
                val feedback = assertNotNull(
                    harness.nodes().firstOrNull { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains("正在处理设置") }
                    },
                    "Feedback banner must be rendered in $style",
                )
                val bar = harness.nodes().first { node -> node.children.any { it.role() == Role.Tab } }
                assertTrue(
                    feedback.boundsInRoot.bottom <= bar.boundsInRoot.top,
                    "Feedback bottom (${feedback.boundsInRoot.bottom}) must be above floating bar (${bar.boundsInRoot.top}) in $style",
                )
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun wideScreenUsesTheNavigationRailForEitherBottomBarStyle() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for (bottomBarStyle in BottomBarStyle.entries) {
                val harness = createOpenRoseAppHarness(
                    connectedI7State(settings(style, bottomBarStyle)),
                    width = 1200,
                )
                try {
                    harness.render()
                    val railItems = harness.tabs()
                    assertTrue(railItems.all { it.boundsInRoot.left < 200f }, "Rail must be on the left in $style/$bottomBarStyle")
                    assertFalse(railItems.any { it.boundsInRoot.top > 800f }, "Bottom bar must be bypassed in $style/$bottomBarStyle")

                    harness.clickText("设置", expectedRole = Role.Tab)
                    harness.render()
                    assertTrue(harness.hasText("组件风格"), "Settings page active in wide $style/$bottomBarStyle")
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun pagerPageChangeSynchronizesTheActiveFloatingTab() = runBlocking {
        for (style in InterfaceStyle.entries) {
            var pagerState: MainTabsPagerState? = null
            val scrollStates = mainPages.associateWith { ScrollState(0) }
            val scene = createOpenRoseUiTestScene(420, 900) {
                val tabsState = rememberMainTabsPagerState()
                pagerState = tabsState
                when (style) {
                    InterfaceStyle.MIUIX -> MiuixMainShell(
                        pagerState = tabsState,
                        wide = false,
                        bottomBarStyle = BottomBarStyle.FLOATING,
                        onNavigate = {},
                        scrollStates = scrollStates,
                        pageContentFor = { page -> BasicText(page.title) },
                    )
                    InterfaceStyle.MATERIAL3 -> MaterialMainShell(
                        pagerState = tabsState,
                        wide = false,
                        bottomBarStyle = BottomBarStyle.FLOATING,
                        onNavigate = {},
                        scrollStates = scrollStates,
                        pageContentFor = { page -> BasicText(page.title) },
                    )
                }
            }
            val harness = OpenRoseUiTestHarness(scene)
            try {
                harness.render()
                fun selectedTabIndex() = harness.tabs().indexOfFirst {
                    it.config.getOrNull(SemanticsProperties.Selected) == true
                }
                assertEquals(0, selectedTabIndex(), "Headphones tab initially selected in $style")

                pagerState!!.pagerState.scrollToPage(1)
                harness.render()
                assertEquals(1, selectedTabIndex(), "System tab selected after page change in $style")

                pagerState!!.pagerState.scrollToPage(2)
                harness.render()
                assertEquals(2, selectedTabIndex(), "Settings tab selected after page change in $style")
            } finally {
                harness.close()
            }
        }
    }

    private fun settings(style: InterfaceStyle, bottomBarStyle: BottomBarStyle) = UserSettings(
        interfaceStyle = style,
        bottomBarStyle = bottomBarStyle,
        pageTransition = PageTransition.NONE,
    )

    private fun OpenRoseUiTestHarness.tabs(): List<SemanticsNode> = nodes().filter { it.role() == Role.Tab }

    private fun OpenRoseUiTestHarness.clearance(): SemanticsNode? = nodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("ShellBottomClearance")
    }

    private fun List<SemanticsNode>.tabFor(label: String): SemanticsNode = first { tab ->
        tab.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) ||
            tab.children.any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) }
    }

    private fun SemanticsNode.role(): Role? = config.getOrNull(SemanticsProperties.Role)

    private fun SemanticsNode.hasText(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
}

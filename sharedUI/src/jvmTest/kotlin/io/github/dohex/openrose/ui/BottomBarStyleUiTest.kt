@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BottomBarStyleUiTest {

    @Test
    fun bottomBarStyleChoiceRowRendersUnderDisplayModeAndUpdatesSettings() {
        for (style in InterfaceStyle.entries) {
            withPersonalizationHarness(
                ControlState(
                    platform = PlatformState(permission = PermissionState.GRANTED),
                ),
                initialUiSettings = UiSettings(interfaceStyle = style, bottomBarStyle = BottomBarStyle.FIXED),
            ) { harness ->
                harness.render()

                assertTrue(harness.hasText("显示模式"), "Personalization page must render display mode section for $style")
                assertTrue(harness.hasText("底栏样式"), "Personalization page must render bottom bar style choice row for $style")
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
            }
        }
    }

    @Test
    fun bottomBarStylePreferenceIsRetainedAcrossInterfaceStyleSwitches() {
        withOpenRoseAppHarness(
            ControlState(
                platform = PlatformState(permission = PermissionState.GRANTED),
            ),
            initialUiSettings = UiSettings(interfaceStyle = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FLOATING),
        ) { harness ->
            harness.render()
            harness.clickTab("设置")
            harness.render()
            harness.openPersonalization()
            assertTrue(harness.hasText("悬浮胶囊"))

            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Material 3 Expressive")
            harness.render()
            assertEquals(InterfaceStyle.MATERIAL3, harness.uiSettings.value.interfaceStyle)
            assertEquals(BottomBarStyle.FLOATING, harness.uiSettings.value.bottomBarStyle)
            assertTrue(harness.hasText("悬浮胶囊"))
            assertTrue(harness.hasText("组件风格"), "Personalization page must remain active after style switch")

            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Miuix")
            harness.render()
            assertEquals(InterfaceStyle.MIUIX, harness.uiSettings.value.interfaceStyle)
            assertEquals(BottomBarStyle.FLOATING, harness.uiSettings.value.bottomBarStyle)
            assertTrue(harness.controlSettingsChanges.isEmpty(), "Style changes must not update control settings")
            assertTrue(harness.hasText("悬浮胶囊"))
        }
    }

    @Test
    fun fixedBottomBarRendersLabelledTabsAndNavigates() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(connectedI7State(), initialUiSettings = settings(style, BottomBarStyle.FIXED)) { harness ->
                harness.render()
                val tabs = harness.tabs()
                assertEquals(3, tabs.size, "Must have 3 bottom bar tabs in $style")
                for (label in listOf("耳机", "状态", "设置")) {
                    assertTrue(tabs.any { it.hasText(label) }, "Fixed tab '$label' must show its label in $style")
                }

                harness.clickTab("设置")
                harness.render()
                assertTrue(harness.hasText("个性化"), "Settings page active after clicking 设置 tab in $style")
            }
        }
    }

    @Test
    fun floatingBottomBarRendersIconTabsAndNavigates() {
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(connectedI7State(), initialUiSettings = settings(style, BottomBarStyle.FLOATING)) { harness ->
                harness.render()
                val tabs = harness.tabs()
                assertEquals(3, tabs.size, "Must have 3 floating bottom bar tabs in $style")
                assertTrue(tabs.none { it.hasText("耳机") }, "Floating tab bar must not render text labels in $style")
                assertEquals(true, tabs.tabFor("耳机").config.getOrNull(SemanticsProperties.Selected))
                assertEquals(false, tabs.tabFor("设置").config.getOrNull(SemanticsProperties.Selected))

                harness.clickContentDescription("设置")
                harness.render()
                assertTrue(harness.hasText("个性化"), "Settings page active after clicking 设置 floating tab in $style")
                val updated = harness.tabs()
                assertEquals(false, updated.tabFor("耳机").config.getOrNull(SemanticsProperties.Selected))
                assertEquals(true, updated.tabFor("设置").config.getOrNull(SemanticsProperties.Selected))
            }
        }
    }

    @Test
    fun onlyFloatingBottomBarReservesTrailingClearance() {
        for (style in InterfaceStyle.entries) {
            withMainShellHarness(style, BottomBarStyle.FLOATING) { floating ->
                floating.render()
                val clearance = assertNotNull(floating.clearance(), "Trailing clearance must be rendered in $style floating mode")
                val bar = floating.nodes().first { node -> node.children.any { it.role() == Role.Tab } }
                assertTrue(
                    clearance.size.height >= bar.size.height,
                    "Clearance (${clearance.size.height}) must be >= floating bar height (${bar.size.height}) in $style",
                )
            }

            withMainShellHarness(style, BottomBarStyle.FIXED) { fixed ->
                fixed.render()
                assertNull(fixed.clearance(), "Fixed bottom bar must not add trailing clearance in $style")
            }
        }
    }

    @Test
    fun feedbackOverlaySitsAboveTheFloatingBottomBar() {
        val operation = SettingOperation(
            id = 100,
            sessionId = 1L,
            setting = DeviceSetting.NOISE_MODE,
            requestedValue = FieldValue.Text("anc"),
            stage = SettingStage.SUBMITTED,
        )
        for (style in InterfaceStyle.entries) {
            withMainShellHarness(style, BottomBarStyle.FLOATING,
                connectedI7State().copy(settingOperations = persistentListOf(operation)),
            ) { harness ->
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
            }
        }
    }

    @Test
    fun wideScreenUsesTheNavigationRailForEitherBottomBarStyle() {
        for (style in InterfaceStyle.entries) {
            for (bottomBarStyle in BottomBarStyle.entries) {
                withOpenRoseAppHarness(
                    connectedI7State(),
                    initialUiSettings = settings(style, bottomBarStyle),
                    width = 1200,
                ) { harness ->
                    harness.render()
                    val railItems = harness.tabs()
                    assertTrue(railItems.all { it.boundsInRoot.left < 200f }, "Rail must be on the left in $style/$bottomBarStyle")
                    assertFalse(railItems.any { it.boundsInRoot.top > 800f }, "Bottom bar must be bypassed in $style/$bottomBarStyle")

                    harness.clickText("设置", expectedRole = Role.Tab)
                    harness.render()
                    harness.openPersonalization()
                    assertTrue(harness.hasText("组件风格"), "Personalization page active in wide $style/$bottomBarStyle")
                    assertFalse(harness.hasText("底栏样式"), "Bottom bar style has no effect beside the rail in $style/$bottomBarStyle")

                    harness.resize(420, 900)
                    harness.render()
                    assertTrue(harness.hasText("组件风格"), "Narrowing the window keeps Personalization active in $style/$bottomBarStyle")
                    assertTrue(harness.hasText("底栏样式"), "Bottom bar style returns with the bottom bar in $style/$bottomBarStyle")
                    harness.clickContentDescription("返回")
                    harness.render()
                    assertTrue(harness.tabs().all { it.boundsInRoot.top > 700f }, "Narrow window shows the bottom bar in $style/$bottomBarStyle")
                }
            }
        }
    }

    @Test
    fun pagerPageChangeSynchronizesTheActiveFloatingTab() {
        for (style in InterfaceStyle.entries) {
            var pagerState: MainTabsPagerState? = null
            val scrollStates = mainPages.associateWith { ScrollState(0) }
            runOpenRoseUiTest(420, 900) {
                setContent {
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
                val harness = OpenRoseUiTestHarness(this)
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
            }
        }
    }

    private fun withMainShellHarness(
        style: InterfaceStyle,
        bottomBarStyle: BottomBarStyle,
        state: ControlState? = null,
        block: suspend (OpenRoseUiTestHarness) -> Unit,
    ) = runOpenRoseUiTest(420, 900) {
        val scrollStates = mainPages.associateWith { ScrollState(0) }
        setContent {
            ProvideInterfaceStyle(style, dark = false) {
                val pagerState = rememberMainTabsPagerState()
                val feedback: @Composable (AppPage) -> Unit = {
                    if (state != null) SettingFeedback(state, state.device) {}
                }
                when (style) {
                    InterfaceStyle.MIUIX -> MiuixMainShell(
                        pagerState = pagerState, wide = false, bottomBarStyle = bottomBarStyle,
                        onNavigate = {}, scrollStates = scrollStates,
                        pageContentFor = { BasicText(it.title) }, pageFeedbackFor = feedback,
                    )
                    InterfaceStyle.MATERIAL3 -> MaterialMainShell(
                        pagerState = pagerState, wide = false, bottomBarStyle = bottomBarStyle,
                        onNavigate = {}, scrollStates = scrollStates,
                        pageContentFor = { BasicText(it.title) }, pageFeedbackFor = feedback,
                    )
                }
            }
        }
        block(OpenRoseUiTestHarness(this))
    }

    private fun settings(style: InterfaceStyle, bottomBarStyle: BottomBarStyle) = UiSettings(
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

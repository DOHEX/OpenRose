@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.rememberPagerState
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiuixFloatingNavigationBarUiTest {

    @Test
    fun miuixFixedBottomBarRendersNavigationBarWithLabelsAndNavigates() = runBlocking {
        val state = mutableStateOf(state(style = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FIXED))
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { next -> state.value = state.value.copy(settings = next) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection") },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            val tabNodes = harness.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            assertEquals(3, tabNodes.size, "Must have 3 bottom bar tabs")

            // Fixed NavigationBar renders labels as Text nodes inside the tabs
            assertTrue(tabNodes.any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "耳机" } })
            assertTrue(tabNodes.any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "系统" } })
            assertTrue(tabNodes.any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "设置" } })

            // Tab clicking works
            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("组件风格"), "Settings page active after clicking 设置 tab")
        } finally {
            harness.close()
        }
    }

    @Test
    fun miuixFloatingBottomBarRendersFloatingNavigationBarAndUpdatesSelectedIndicator() = runBlocking {
        val state = mutableStateOf(state(style = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FLOATING))
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { next -> state.value = state.value.copy(settings = next) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection") },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            val tabNodes = harness.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            assertEquals(3, tabNodes.size, "Must have 3 floating bottom bar tabs")

            // In FLOATING mode, FloatingNavigationBarItem does not render text labels;
            // instead icons have contentDescription
            assertTrue(
                tabNodes.none { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "耳机" } },
                "Floating tab bar must not render text labels",
            )

            fun tabFor(label: String): SemanticsNode = tabNodes.first { tab ->
                tab.children.any { child ->
                    child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
                } || tab.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
            }

            val headphonesTab = tabFor("耳机")
            val settingsTab = tabFor("设置")

            assertEquals(true, headphonesTab.config.getOrNull(SemanticsProperties.Selected), "Initial tab is Headphones")
            assertEquals(false, settingsTab.config.getOrNull(SemanticsProperties.Selected), "Settings tab initially not selected")

            // Tab clicking navigates and synchronizes with pagerState
            harness.clickContentDescription("设置")
            harness.render()

            assertTrue(harness.hasText("组件风格"), "Settings page active after clicking 设置 floating tab")

            val updatedTabs = harness.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            fun updatedTabFor(label: String): SemanticsNode = updatedTabs.first { tab ->
                tab.children.any { child ->
                    child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
                } || tab.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
            }

            assertEquals(false, updatedTabFor("耳机").config.getOrNull(SemanticsProperties.Selected), "Headphones tab no longer selected")
            assertEquals(true, updatedTabFor("设置").config.getOrNull(SemanticsProperties.Selected), "Settings tab is selected")
        } finally {
            harness.close()
        }
    }

    @Test
    fun contentExtendsBehindFloatingBarWithTrailingClearance() = runBlocking {
        val state = mutableStateOf(state(style = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FLOATING))
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { next -> state.value = state.value.copy(settings = next) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection") },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            // In FLOATING mode:
            // 1. Trailing clearance spacer is rendered with height matching the floating bar
            val clearanceNode = harness.nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("ShellBottomClearance")
            }
            assertTrue(clearanceNode != null, "Trailing clearance spacer must be rendered in floating mode")
            assertTrue(clearanceNode.size.height > 0, "Clearance spacer height must be greater than 0")

            // 2. The clearance spacer height accounts for the floating bottom bar
            val floatingBar = harness.nodes().first { node ->
                node.children.any { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            }
            assertTrue(
                clearanceNode.size.height >= floatingBar.size.height,
                "Clearance spacer height (${clearanceNode.size.height}) must be >= floating bar height (${floatingBar.size.height})",
            )
        } finally {
            harness.close()
        }

        // In FIXED mode: no clearance spacer is needed or rendered
        val fixedState = mutableStateOf(state(style = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FIXED))
        val fixedScene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = fixedState.value,
                onSettingsChange = { next -> fixedState.value = fixedState.value.copy(settings = next) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection") },
            )
        }
        val fixedHarness = OpenRoseUiTestHarness(fixedScene)
        try {
            fixedHarness.render()
            val fixedClearance = fixedHarness.nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("ShellBottomClearance")
            }
            assertTrue(fixedClearance == null, "Fixed bottom bar should not have trailing clearance spacer")
        } finally {
            fixedHarness.close()
        }
    }

    @Test
    fun wideScreenRendersNavigationRailAndBypassesBottomBarRegardlessOfStyle() = runBlocking {
        for (style in listOf(BottomBarStyle.FIXED, BottomBarStyle.FLOATING)) {
            val state = mutableStateOf(state(style = InterfaceStyle.MIUIX, bottomBarStyle = style))
            val scene = createOpenRoseUiTestScene(1200, 900) {
                OpenRoseApp(
                    state = state.value,
                    onSettingsChange = { next -> state.value = state.value.copy(settings = next) },
                    onRequestPermission = {},
                    onOpenSystemSettings = {},
                    onStartScan = {},
                    onStopScan = {},
                    onConnect = {},
                    onDisconnect = {},
                    onSet = { _, _ -> },
                    onAddDeviceConnect = { error("Unexpected add-device connection") },
                )
            }
            val harness = OpenRoseUiTestHarness(scene)
            try {
                harness.render()
                // Wide navigation rail renders items with title
                val railItems = harness.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                // In wide mode, NavigationRail items are at the left edge (boundsInRoot.left < 200)
                assertTrue(railItems.all { it.boundsInRoot.left < 200f }, "NavigationRail items should be on the left in $style")
                // No bottom bar at the bottom center of the screen
                assertFalse(railItems.any { it.boundsInRoot.top > 800f }, "Bottom bar must be bypassed in wide mode for $style")

                // NavigationRail tab clicking works
                harness.clickText("设置", expectedRole = Role.Tab)
                harness.render()
                assertTrue(harness.hasText("组件风格"), "Settings page active in wide mode for $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun feedbackOverlayAppliesFloatingBottomClearanceInFloatingMode() = runBlocking {
        val operation = SettingOperation(
            id = 100,
            sessionId = 1L,
            setting = DeviceSetting.NOISE_MODE,
            requestedValue = FieldValue.Text("anc"),
            stage = SettingStage.SUBMITTED,
        )
        val state = mutableStateOf(
            state(style = InterfaceStyle.MIUIX, bottomBarStyle = BottomBarStyle.FLOATING).copy(
                settingOperations = listOf(operation),
            ),
        )
        val scene = createOpenRoseUiTestScene(420, 900) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { next -> state.value = state.value.copy(settings = next) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection") },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            val feedbackNode = harness.nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains("正在处理设置") }
            }
            assertTrue(feedbackNode != null, "Feedback banner must be rendered")

            val floatingBar = harness.nodes().first { node ->
                node.children.any { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            }

            // In floating mode, feedback overlay must have clearance applied so its bottom edge
            // sits above the floating bar without being obscured
            assertTrue(
                feedbackNode.boundsInRoot.bottom <= floatingBar.boundsInRoot.top,
                "Feedback overlay bottom (${feedbackNode.boundsInRoot.bottom}) must be <= floating bar top (${floatingBar.boundsInRoot.top})",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun pagerPageChangeSynchronizesActiveTabInFloatingMode() = runBlocking {
        var mainTabsPagerState: MainTabsPagerState? = null
        val scrollStates = mainPages.associateWith { ScrollState(0) }
        val scene = createOpenRoseUiTestScene(420, 900) {
            val tabsState = rememberMainTabsPagerState()
            mainTabsPagerState = tabsState
            MiuixMainShell(
                pagerState = tabsState,
                wide = false,
                bottomBarStyle = BottomBarStyle.FLOATING,
                onNavigate = {},
                scrollStates = scrollStates,
                pageContentFor = { page -> Text(page.title) },
            )
        }
        val harness = OpenRoseUiTestHarness(scene)
        try {
            harness.render()
            fun selectedTabIndex(): Int {
                val tabs = harness.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                return tabs.indexOfFirst { it.config.getOrNull(SemanticsProperties.Selected) == true }
            }

            assertEquals(0, selectedTabIndex(), "Headphones tab initially selected")

            // Simulate pager swiping / page change to page 1 ("系统")
            mainTabsPagerState!!.pagerState.scrollToPage(1)
            harness.render()
            assertEquals(1, selectedTabIndex(), "System tab selected after page change to 1")

            // Simulate pager swiping / page change to page 2 ("设置")
            mainTabsPagerState!!.pagerState.scrollToPage(2)
            harness.render()
            assertEquals(2, selectedTabIndex(), "Settings tab selected after page change to 2")
        } finally {
            harness.close()
        }
    }

    private fun state(
        style: InterfaceStyle = InterfaceStyle.MIUIX,
        bottomBarStyle: BottomBarStyle = BottomBarStyle.FIXED,
        connected: Boolean = true,
    ): ControlState {
        val profile = ModelProfile.I7_RS_V2
        val deviceId = DeviceId("miuix-bar-test-i7")
        val device = DeviceState(
            deviceId = deviceId,
            sessionId = 1L,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = DeviceField.entries.toSet(),
                writableSettings = DeviceSetting.entries.toSet(),
                noiseControl = noiseControlCapabilities(profile),
                equalizer = equalizerCapabilities(profile),
                touchControl = touchControlCapabilities(profile),
                audioControl = audioControlCapabilities(profile),
                promptVolumeLevels = promptVolumeLevels(profile),
                gainLevels = setOf(0, 1, 2),
                hearingProtectionLevels = (0..6).toSet(),
            ),
            fields = mapOf(
                DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(90), "90"),
                DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(90), "90"),
                DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(80), "80"),
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
            ),
        )
        return ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                module = ModuleAvailability.NotEnabled,
                hostPlatform = HostPlatform.ANDROID,
                systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                controlTransportApplicability = PlatformApplicability.APPLICABLE,
            ),
            connection = if (connected) {
                ConnectionState.Connected(deviceId, 1L, profile, ConnectionPhase.READY)
            } else {
                ConnectionState.Disconnected
            },
            settings = UserSettings(
                interfaceStyle = style,
                bottomBarStyle = bottomBarStyle,
                appearance = Appearance.SYSTEM,
                pageTransition = PageTransition.NONE,
            ),
            device = device,
        )
    }
}

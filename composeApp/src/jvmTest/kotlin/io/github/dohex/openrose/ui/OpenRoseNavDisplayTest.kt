@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseNavDisplayTest {

    @Test
    fun secondaryRoutesPushFullScreenAndHideBottomBar() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initial = state(connected = true).copy(
                settings = UserSettings(interfaceStyle = style, appearance = Appearance.SYSTEM),
            )
            val harness = createHarness(420, 900, initial)
            try {
                harness.render()
                // On root main tab: bottom navigation bar tabs exist
                assertTrue(harness.hasText("系统"), "Main tab should have 系统 navigation tab in $style")
                assertTrue(harness.hasText("设置"), "Main tab should have 设置 navigation tab in $style")

                // Push a remaining secondary route.
                harness.clickText("触控操作")
                harness.render()

                // Inside secondary route: title and back button exist, bottom navigation tabs are HIDDEN
                assertTrue(harness.hasText("触控操作"))
                assertTrue(harness.hasContentDescription("返回"))
                assertFalse(harness.hasText("系统"), "Bottom navigation bar must be hidden in secondary route in $style")
                assertFalse(harness.hasText("设置"), "Bottom navigation bar must be hidden in secondary route in $style")

                // Click back button: pops secondary route, restores root main tab with bottom bar
                harness.clickContentDescription("返回")
                harness.render()

                assertTrue(harness.hasText("均衡器"), "Home card should be visible")
                assertTrue(harness.hasText("系统"), "Bottom navigation bar must be restored on returning to main in $style")
                assertTrue(harness.hasText("设置"), "Bottom navigation bar must be restored on returning to main in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun tabSwitchingUpdatesInPlaceAndBackKeyReturnsToHeadphones() = runBlocking {
        val initial = state(connected = true)
        val harness = createHarness(420, 900, initial)
        try {
            harness.render()
            assertTrue(harness.hasText("均衡器"), "Initially on Headphones tab")

            // Switch to Settings tab in-place
            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("界面风格"), "Now on Settings tab")

            // Send Escape key to trigger back navigation
            harness.sendKey(Key.Escape)
            harness.render()

            // Should have returned to Headphones home tab!
            assertTrue(harness.hasText("均衡器"), "Pressing back from Settings must return to Headphones home tab")
        } finally {
            harness.close()
        }
    }

    @Test
    fun interfaceStyleSwitchPreservesNavigationStack() = runBlocking {
        val initial = state(connected = true).copy(
            settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, appearance = Appearance.SYSTEM),
        )
        val harness = createHarness(420, 900, initial)
        try {
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
        } finally {
            harness.close()
        }
    }

    @Test
    fun pageTransitionPreferencesApplyCleanly() = runBlocking {
        for (transition in PageTransition.entries) {
            val initial = state(connected = true).copy(
                settings = UserSettings(pageTransition = transition),
            )
            val harness = createHarness(420, 900, initial)
            try {
                harness.render()
                harness.clickText("触控操作")
                harness.render()
                assertTrue(harness.hasText("触控操作"), "Navigation should work for transition $transition")
                harness.clickContentDescription("返回")
                harness.render()
                assertTrue(harness.hasText("均衡器"))
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(
        width: Int,
        height: Int,
        initialState: ControlState,
    ): NavTestHarness {
        val state = mutableStateOf(initialState)
        val settingsChanges = mutableListOf<UserSettings>()
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
            coroutineContext = currentCoroutineContext(),
        ) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings ->
                    settingsChanges += settings
                    state.value = state.value.copy(settings = settings)
                },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
            )
        }
        return NavTestHarness(scene, state, settingsChanges)
    }

    private fun state(connected: Boolean): ControlState {
        val profile = ModelProfile.I7_RS_V2
        val deviceId = DeviceId("nav-test-i7")
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
            settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, appearance = Appearance.SYSTEM),
            device = device,
        )
    }

    private class NavTestHarness(
        val scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val settingsChanges: MutableList<UserSettings>,
    ) {
        private var nextFrameTimeNanos = 0L

        suspend fun render() {
            repeat(10) {
                kotlinx.coroutines.delay(35)
                scene.render(nextFrameTimeNanos).close()
                nextFrameTimeNanos += 35_000_000L
            }
        }

        fun close() = scene.close()

        fun nodes(): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                result += node
                node.children.forEach(::collect)
            }
            scene.semanticsOwners.forEach { collect(it.rootSemanticsNode) }
            return result
        }

        fun hasText(expected: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
        }

        fun hasContentDescription(expected: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
        }

        fun hasRole(role: Role): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Role) == role
        }

        fun clickTab(text: String) = clickText(text, expectedRole = Role.Tab)

        fun clickText(text: String, expectedRole: Role? = null) {
            val textNodes = nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }
            for (textNode in textNodes) {
                var current: SemanticsNode? = textNode
                while (current != null) {
                    val role = current.config.getOrNull(SemanticsProperties.Role)
                    if (expectedRole == null || role == expectedRole) {
                        val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
                        if (action != null) {
                            check(action()) { "Click action for '$text' returned false" }
                            return
                        }
                    }
                    current = current.parent
                }
            }
            error("No clickable node found for text '$text'")
        }

        fun clickContentDescription(expected: String) {
            val node = nodes().firstOrNull { semantics ->
                semantics.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
            } ?: error("No node found with content description '$expected'")
            var current: SemanticsNode? = node
            while (current != null) {
                val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
                if (action != null) {
                    check(action()) { "Click action returned false" }
                    return
                }
                current = current.parent
            }
            error("No clickable node found for content description '$expected'")
        }

        fun sendKey(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        }
    }
}

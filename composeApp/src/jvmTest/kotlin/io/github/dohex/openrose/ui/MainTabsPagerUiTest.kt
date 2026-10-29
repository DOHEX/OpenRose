@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
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

class MainTabsPagerUiTest {

    @Test
    fun horizontalPagerNavigatesThroughAllThreeMainTabsUnderBothStyles() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initial = state(connected = true).copy(
                settings = UserSettings(interfaceStyle = style, appearance = Appearance.SYSTEM),
            )
            val harness = createHarness(420, 900, initial)
            try {
                harness.render()
                assertTrue(harness.hasText("均衡器"), "Initial tab is Headphones in $style")
                assertFalse(harness.hasText("组件风格"), "Settings must not be active on startup in $style")

                // Navigate to System integration tab
                harness.clickTab("系统")
                harness.render()
                assertTrue(harness.hasText("运行状态与诊断"), "System integration active in $style")
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
        val initial = state(connected = true).copy(
            settings = UserSettings(
                interfaceStyle = InterfaceStyle.MIUIX,
                pageTransition = PageTransition.NONE,
            ),
        )
        val harness = createHarness(420, 900, initial)
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

    @Test
    fun wideSidebarNavigationSwitchesTabsConsistently() = runBlocking {
        val initial = state(connected = true).copy(
            settings = UserSettings(interfaceStyle = InterfaceStyle.MATERIAL3),
        )
        val harness = createHarness(1200, 1000, initial)
        try {
            harness.render()
            assertTrue(harness.hasText("均衡器"))

            harness.clickTab("系统集成")
            harness.render()
            assertTrue(harness.hasText("运行状态与诊断"))

            harness.clickTab("我的耳机")
            harness.render()
            assertTrue(harness.hasText("均衡器"))
        } finally {
            harness.close()
        }
    }

    private suspend fun createHarness(
        width: Int,
        height: Int,
        initialState: ControlState,
    ): PagerTestHarness {
        val state = mutableStateOf(initialState)
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
            coroutineContext = currentCoroutineContext(),
        ) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings -> state.value = state.value.copy(settings = settings) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
            )
        }
        return PagerTestHarness(scene, state)
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

    private class PagerTestHarness(
        val scene: ImageComposeScene,
        val state: MutableState<ControlState>,
    ) {
        private var nextFrameTimeNanos = 0L

        suspend fun render(frames: Int = 10) {
            repeat(frames) {
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

        fun sendKey(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        }
    }
}

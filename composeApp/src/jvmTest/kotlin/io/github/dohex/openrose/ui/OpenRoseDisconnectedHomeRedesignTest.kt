@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
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

class OpenRoseDisconnectedHomeRedesignTest {

    @Test
    fun disconnectedHomeHidesTopPlusButtonAndMisleadingDiagnosticsCard() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initial = state(connected = false).copy(
                settings = UserSettings(interfaceStyle = style),
            )
            val harness = createHarness(420, 900, initial)
            try {
                harness.render()
                // Top bar "+" action must NOT be present when disconnected
                assertFalse(harness.hasContentDescription("添加耳机"), "Top bar + button must be hidden when disconnected in $style")

                // Misleading "连接与运行状态" card must NOT be present
                assertFalse(harness.hasText("连接与运行状态"), "Misleading connection status card must be removed in $style")

                // Title must be present
                assertTrue(harness.hasText("连接你的 ROSE 耳机"))
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun disconnectedHomeShowsPairedDeviceCardWithDirectConnect() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val pairedDevice = DeviceCandidate(
                deviceId = DeviceId("paired-i5-1"),
                name = "ROSE EarFree i5",
                rssi = null,
                profile = ModelProfile.I5_ZT,
                manufacturerDataHex = "",
            )
            val initial = state(connected = false).copy(
                settings = UserSettings(interfaceStyle = style),
                bondedDevices = listOf(pairedDevice),
            )
            val harness = createHarness(420, 900, initial)
            try {
                harness.render()
                // Paired devices section and device name
                assertTrue(harness.hasText("已配对设备"), "Should display 已配对设备 header in $style")
                assertTrue(harness.hasText("ROSE EarFree i5"), "Should display paired device name in $style")
                assertTrue(harness.hasText("ZT"), "Should display model configuration in $style")

                // Direct connect button
                assertTrue(harness.hasText("连接"), "Should display 连接 button for paired device in $style")

                // Clicking connect invokes onConnect
                harness.clickText("连接")
                assertEquals(listOf(DeviceId("paired-i5-1")), harness.connectRequests)

                // Secondary scan action changes to 扫描新设备 when paired devices exist
                assertTrue(harness.hasText("扫描新设备"), "Scan action should be secondary 扫描新设备 in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun multiplePairedDevicesDisplayDistinctNamesAndProfiles() = runBlocking {
        val paired1 = DeviceCandidate(
            deviceId = DeviceId("paired-i5"),
            name = "My i5 Earbuds",
            rssi = null,
            profile = ModelProfile.I5_ZT,
            manufacturerDataHex = "",
        )
        val paired2 = DeviceCandidate(
            deviceId = DeviceId("paired-i7"),
            name = "My i7 Headset",
            rssi = null,
            profile = ModelProfile.I7_RS_V2,
            manufacturerDataHex = "",
        )
        val initial = state(connected = false).copy(
            bondedDevices = listOf(paired1, paired2),
        )
        val harness = createHarness(420, 900, initial)
        try {
            harness.render()
            assertTrue(harness.hasText("My i5 Earbuds"))
            assertTrue(harness.hasText("ZT"))
            assertTrue(harness.hasText("My i7 Headset"))
            assertTrue(harness.hasText("RS Common V2"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun primaryDashboardHidesSecondaryDisconnectButtonWhileDetailsRetainsIt() = runBlocking {
        val initial = state(connected = true)
        val harness = createHarness(420, 900, initial)
        try {
            harness.render()
            // On primary connected headphones dashboard:
            // "断开控制连接" is NOT present as an explicit button on the main dashboard content
            // (It is in the top-bar more menu, but not on the primary surface)
            assertFalse(harness.hasText("断开控制连接"), "Primary dashboard should not show secondary Disconnect button")

            // Navigate to 音频与设备
            harness.clickText("音频与设备")
            harness.render()
            assertFalse(harness.hasText("断开控制连接"), "Audio page should not show secondary Disconnect button")

            // Navigate to 设备详情
            harness.clickText("设备详情")
            harness.render()

            // In 设备详情: "断开控制连接" IS present in the ConnectionDetailsCard!
            assertTrue(harness.hasText("断开控制连接"), "Device Details must retain 断开控制连接 button in advanced section")

            // Clicking disconnect invokes onDisconnect
            harness.clickText("断开控制连接")
            assertEquals(1, harness.disconnectCount)
        } finally {
            harness.close()
        }
    }

    private suspend fun createHarness(
        width: Int,
        height: Int,
        initialState: ControlState,
    ): DisconnectedHomeHarness {
        val state = mutableStateOf(initialState)
        val connectRequests = mutableListOf<DeviceId>()
        var disconnectCount = 0
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
            coroutineContext = currentCoroutineContext(),
        ) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { state.value = state.value.copy(settings = it) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = { connectRequests += it },
                onDisconnect = { disconnectCount++ },
                onSet = { _, _ -> },
            )
        }
        return DisconnectedHomeHarness(scene, state, connectRequests) { disconnectCount }
    }

    private fun state(connected: Boolean): ControlState {
        val profile = ModelProfile.I7_RS_V2
        val deviceId = DeviceId("test-i7")
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
            device = if (connected) device else null,
        )
    }

    private class DisconnectedHomeHarness(
        val scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val connectRequests: MutableList<DeviceId>,
        val disconnectCountSupplier: () -> Int,
    ) {
        val disconnectCount get() = disconnectCountSupplier()
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

        fun clickText(text: String) {
            val textNodes = nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }
            for (textNode in textNodes) {
                var current: SemanticsNode? = textNode
                while (current != null) {
                    val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
                    if (action != null) {
                        check(action()) { "Click action for '$text' returned false" }
                        return
                    }
                    current = current.parent
                }
            }
            error("No clickable node found for text '$text'")
        }
    }
}

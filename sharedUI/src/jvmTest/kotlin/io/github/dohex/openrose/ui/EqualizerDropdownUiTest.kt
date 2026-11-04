@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerDropdownUiTest {
    @Test
    fun supportedPresetsAppearInOrderOnTheHomePage() {
        val scenarios = listOf(
            Triple(ModelProfile.I5_ZT, listOf(1, 5, 2), listOf("弱水经典 · POP", "日系柔情 · HIFI", "乐器大师 · ROCK", "清新空灵 · LIGHT")),
            Triple(ModelProfile.I7_RS_V2, listOf(1, 5, 1), listOf("弱水经典 · POP", "原声再现 · HIFI", "古典乐器 · ROCK", "日系甜美 · LIGHT")),
            Triple(ModelProfile.I7_RS_V2, listOf(1, 5, 2), listOf("弱水经典 · POP", "原声再现 · HIFI", "古典乐器 · ROCK", "日系甜美 · LIGHT", "欧美流行 · EAP")),
        )
        for (style in InterfaceStyle.entries) for ((profile, firmware, labels) in scenarios) {
            withHarness(style, profile, firmware) { harness ->
                harness.render()
                harness.clickText("均衡器")
                harness.render()
                assertTrue(harness.hasText("设备详情"), "picker remains on home in $style")
                labels.forEach { assertTrue(harness.hasText(it), "$it missing in $style") }
                assertEquals(labels.size == 5, harness.hasText("欧美流行 · EAP"))
                val optionTops = labels.map { label ->
                    harness.nodes().last { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
                    }.boundsInRoot.top
                }
                assertTrue(optionTops.zipWithNext().all { (a, b) -> a < b }, "preset order in $style")
            }
        }
    }

    @Test
    fun selectionWaitsForDeviceConfirmation() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2)) { harness ->
                harness.render()
                assertTrue(harness.hasText("弱水经典 · POP"))
                harness.clickText("均衡器")
                harness.render()
                assertTrue(harness.hasSelectedText("弱水经典 · POP"))
                harness.clickText("原声再现 · HIFI")
                harness.render()
                assertEquals(listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.EQ to FieldValue.Text("hifi")), harness.writes)
                assertTrue(harness.hasText("弱水经典 · POP"), "request is not confirmation")
                harness.clickText("均衡器")
                harness.render()
                assertTrue(harness.hasSelectedText("弱水经典 · POP"))
                harness.sendKey(Key.Escape)
                val device = requireNotNull(harness.state.value.device)
                harness.state.value = harness.state.value.copy(device = device.copy(fields = device.fields +
                    (DeviceField.EQ to FieldReading.Known(FieldValue.Text("hifi"), "01"))))
                harness.render()
                assertTrue(harness.hasText("原声再现 · HIFI"))
                harness.clickText("均衡器")
                harness.render()
                assertTrue(harness.hasSelectedText("原声再现 · HIFI"))
                harness.clickText("原声再现 · HIFI")
                harness.render()
                assertEquals(1, harness.writes.size, "confirmed selection is not sent again")
                for (nextStyle in listOf(
                    if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX,
                    style,
                )) {
                    harness.state.value = harness.state.value.copy(
                        settings = harness.state.value.settings.copy(interfaceStyle = nextStyle),
                    )
                    harness.render()
                    assertTrue(harness.hasText("原声再现 · HIFI"), "confirmed preset should remain visible in $nextStyle")
                    assertEquals(1, harness.writes.size, "switching to $nextStyle must not write a preset")
                    harness.clickText("均衡器")
                    harness.render()
                    assertTrue(harness.hasSelectedText("原声再现 · HIFI"), "confirmed preset should stay selected in $nextStyle")
                    harness.clickText("原声再现 · HIFI")
                    harness.render()
                    assertEquals(1, harness.writes.size, "reselecting the confirmed preset must not write in $nextStyle")
                }
            }
        }
    }

    @Test
    fun pickerIsDisabledWhileInitializingOrSettingIsPending() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I5_ZT, listOf(1, 5, 2)) { harness ->
                val ready = harness.state.value
                val connection = ready.connection as ConnectionState.Connected
                harness.state.value = ready.copy(connection = connection.copy(phase = ConnectionPhase.INITIALIZING))
                harness.render()
                if (style == InterfaceStyle.MATERIAL3) {
                    assertTrue(harness.hasDisabledText("均衡器"), "initializing in $style")
                } else {
                    runCatching { harness.clickText("均衡器") }
                    harness.render()
                    assertFalse(harness.hasText("日系柔情 · HIFI"), "initializing in $style")
                }
                harness.state.value = ready.copy(settingOperations = persistentListOf(SettingOperation(
                    id = 1, sessionId = 42L, setting = DeviceSetting.EQ,
                    requestedValue = FieldValue.Text("hifi"), stage = SettingStage.SUBMITTED,
                )))
                harness.render()
                if (style == InterfaceStyle.MATERIAL3) {
                    assertTrue(harness.hasDisabledText("均衡器"), "pending in $style")
                } else {
                    runCatching { harness.clickText("均衡器") }
                    harness.render()
                    assertFalse(harness.hasText("日系柔情 · HIFI"), "pending in $style")
                }
                assertTrue(harness.writes.isEmpty())
            }
        }
    }

    @Test
    fun unsupportedOrUnwritableEqualizerIsHidden() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I5_ZT, listOf(1, 5, 2)) { harness ->
                val device = requireNotNull(harness.state.value.device)
                for (capabilities in listOf(
                    device.capabilities.copy(equalizer = null),
                    device.capabilities.copy(equalizer = EqualizerCapabilities(persistentSetOf())),
                    device.capabilities.copy(writableSettings = persistentSetOf()),
                )) {
                    harness.state.value = harness.state.value.copy(device = device.copy(capabilities = capabilities))
                    harness.render()
                    assertFalse(harness.hasText("均衡器"))
                }
            }
        }
    }

    private fun withHarness(
        style: InterfaceStyle,
        profile: ModelProfile,
        parts: List<Int>,
        block: suspend (EqHarness) -> Unit,
    ) {
        val id = DeviceId("eq-test")
        val firmware = FieldValue.Firmware(parts, parts.joinToString(""))
        val device = DeviceState(id, 42L, profile, DeviceCapabilities(
            readableFields = persistentSetOf(DeviceField.EQ), writableSettings = persistentSetOf(DeviceSetting.EQ),
            equalizer = equalizerCapabilities(profile, firmware),
        ), fields = persistentMapOf(DeviceField.EQ to FieldReading.Known(FieldValue.Text("pop"), "00")))
        val state = mutableStateOf(ControlState(
            platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
            connection = ConnectionState.Connected(id, 42L, profile, ConnectionPhase.READY),
            settings = UserSettings(interfaceStyle = style), device = device,
        ))
        val writes = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        runOpenRoseUiTest(420, 900) {
            setContent {
                OpenRoseApp(state.value, null, {}, {}, {}, {}, {}, {}, {}, { setting, value -> writes += setting to value },
                    onAddDeviceConnect = { error("Unexpected connection") })
            }
            block(EqHarness(this, state, writes))
        }
    }

    private class EqHarness(
        scene: OpenRoseUiTestScene,
        val state: androidx.compose.runtime.MutableState<ControlState>,
        val writes: MutableList<Pair<DeviceSetting, FieldValue>>,
    ) : OpenRoseUiTestHarness(scene)
}

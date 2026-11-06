package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseChoiceDialogUiTest {
    @Test
    fun materialStyleChoiceRowsShareTheDialogSurface() {
        withPersonalizationHarness(
            ControlState(platform = PlatformState(PermissionState.GRANTED)),
            UiSettings(interfaceStyle = InterfaceStyle.MATERIAL3, appearance = Appearance.LIGHT),
        ) { harness ->
            harness.render()
            harness.clickText("组件风格")
            harness.render()
            val firstOption = harness.nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Miuix" }
            }.boundsInWindow
            val image = harness.snapshot()
            val y = firstOption.top.toInt() + 4
            assertEquals(
                image.getRGB(firstOption.left.toInt() - 2, y),
                image.getRGB(firstOption.left.toInt() + 2, y),
                "the visible option background should blend with its dialog surface",
            )
        }
    }

    @Test
    fun deviceChoiceWaitsForConfirmationAndDoesNotInventAnUnknownValue() {
        for (style in InterfaceStyle.entries) {
            for (reading in listOf<FieldReading>(
                FieldReading.Known(FieldValue.Number(1), "01"),
                FieldReading.Unknown("ff"),
            )) {
                withConnectedHarness(style, reading) { harness ->
                    harness.render()
                    if (style == InterfaceStyle.MIUIX && reading is FieldReading.Known) {
                        assertEquals(1, harness.countText("1 档"), "the current choice is displayed once")
                        assertFalse(harness.hasText("1"), "the raw value is not repeated as a summary")
                    }
                    harness.clickText("提示音音量")
                    harness.render()
                    if (reading is FieldReading.Unknown) {
                        assertFalse(
                            harness.nodes().any { node ->
                                node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                    node.config.getOrNull(SemanticsProperties.Selected) == true
                            },
                            "unknown device state has no selected option in $style",
                        )
                        harness.clickText("取消")
                        harness.render()
                        assertTrue(harness.settingWrites.isEmpty(), "cancel must not submit an unknown value")
                        harness.clickText("提示音音量")
                        harness.render()
                    } else {
                        assertTrue(harness.hasSelectedText("1 档"), "the confirmed option is selected in $style")
                    }
                    harness.clickText("2 档")
                    harness.render()
                    assertEquals(listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.PROMPT_VOLUME to FieldValue.Number(2)), harness.settingWrites)
                    if (reading is FieldReading.Unknown) assertTrue(harness.hasText("当前状态未知"))

                    harness.clickText("提示音音量")
                    harness.render()
                    if (reading is FieldReading.Unknown) {
                        assertFalse(
                            harness.nodes().any { node ->
                                node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                                    node.config.getOrNull(SemanticsProperties.Selected) == true
                            },
                            "the request is not a confirmation in $style",
                        )
                    } else {
                        assertTrue(harness.hasSelectedText("1 档"), "the previous confirmed option remains selected in $style")
                    }
                    harness.clickText(if (reading is FieldReading.Unknown) "取消" else "1 档")
                    harness.render()
                    val device = requireNotNull(harness.state.value.device)
                    harness.state.value = harness.state.value.copy(
                        device = device.copy(
                            fields = device.fields +
                                (DeviceField.PROMPT_VOLUME to FieldReading.Known(FieldValue.Number(2), "02")),
                        ),
                    )
                    harness.render()
                    harness.clickText("提示音音量")
                    harness.render()
                    assertTrue(harness.hasSelectedText("2 档"), "device confirmation selects the new option in $style")
                    harness.clickText("2 档")
                    harness.render()
                    assertEquals(1, harness.settingWrites.size, "choosing the confirmed option does not resend")
                }
            }
        }
    }

    @Test
    fun styleChoiceCanBeDismissedAndSelectedWithKeyboard() {
        for (style in InterfaceStyle.entries) {
            withConnectedHarness(style, FieldReading.Known(FieldValue.Number(1), "01"), preferences = true) { harness ->
                harness.render()
                val originalSettings = harness.uiSettings.value
                harness.focus("组件风格")
                harness.sendKey(Key.Enter)
                harness.render()
                val nextLabel = if (style == InterfaceStyle.MIUIX) "Material 3 Expressive" else "Miuix"
                assertTrue(harness.hasText(nextLabel), "Enter opens choices in $style")
                harness.sendKey(Key.Escape)
                harness.render()
                assertFalse(harness.hasText(nextLabel), "Escape dismisses without choosing in $style")
                assertTrue(harness.settingsWrites.isEmpty())
                assertTrue(harness.settingWrites.isEmpty())

                harness.focus("组件风格")
                harness.sendKey(Key.Spacebar)
                harness.render()
                harness.focus("Material 3 Expressive")
                harness.sendKey(Key.Tab, shift = true)
                harness.render()
                assertTrue(harness.focusedText().contains("Miuix"), "Shift+Tab moves to the previous choice in $style")
                harness.sendKey(Key.Tab)
                harness.render()
                assertTrue(harness.focusedText().contains("Material 3 Expressive"), "Tab returns to the next choice in $style")
                harness.focus(nextLabel)
                harness.sendKey(Key.Enter)
                harness.render()
                val next = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX
                assertEquals(next, harness.uiSettings.value.interfaceStyle)
                assertEquals(listOf(originalSettings.copy(interfaceStyle = next)), harness.settingsWrites)
                assertTrue(harness.settingWrites.isEmpty())
                assertEquals(
                    ConnectionState.Connected(harness.deviceId, 42L, ModelProfile.I7_RS_V2, ConnectionPhase.READY),
                    harness.state.value.connection,
                )
                assertEquals(0, harness.otherActions)
            }
        }
    }

    @Test
    fun bassAndLanguageChoicesUseConfirmedValuesInBothStyles() {
        for (style in InterfaceStyle.entries) {
            withConnectedHarness(style, FieldReading.Unknown("ff")) { harness ->
                val device = requireNotNull(harness.state.value.device)
                harness.state.value = harness.state.value.copy(
                    device = device.copy(
                        capabilities = DeviceCapabilities(
                            readableFields = persistentSetOf(DeviceField.EQ, DeviceField.EQ_SLOT, DeviceField.PROMPT_LANGUAGE),
                            writableSettings = persistentSetOf(DeviceSetting.BASS_BOOST, DeviceSetting.PROMPT_LANGUAGE),
                            bassBoostLevels = persistentSetOf(0, 1, 2, 3, 4),
                            promptLanguages = persistentSetOf("chinese", "english"),
                        ),
                        fields = persistentMapOf(
                            DeviceField.EQ to FieldReading.Known(FieldValue.Text("custom_slot"), "0501"),
                            DeviceField.EQ_SLOT to FieldReading.Known(FieldValue.Number(1), "0501"),
                            DeviceField.PROMPT_LANGUAGE to FieldReading.Known(FieldValue.Text("chinese"), "00"),
                        ),
                    ),
                )
                harness.render()
                harness.clickText("低音增强")
                harness.render()
                assertTrue(harness.hasSelectedText("1 档"))
                harness.clickText("2 档")
                harness.render()
                harness.clickText("提示音语言")
                harness.render()
                assertTrue(harness.hasSelectedText("中文"))
                harness.clickText("英文")
                harness.render()
                assertEquals(
                    listOf(DeviceSetting.BASS_BOOST to FieldValue.Number(2), DeviceSetting.PROMPT_LANGUAGE to FieldValue.Text("english")),
                    harness.settingWrites,
                )
            }
        }
    }

    private fun withConnectedHarness(
        style: InterfaceStyle,
        reading: FieldReading,
        preferences: Boolean = false,
        block: suspend (ConnectedHarness) -> Unit,
    ) = runOpenRoseUiTest(420, 900) {
        val id = DeviceId("dialog-test-i7")
        val profile = ModelProfile.I7_RS_V2
        val device = DeviceState(
            deviceId = id,
            sessionId = 42L,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = persistentSetOf(DeviceField.PROMPT_VOLUME),
                writableSettings = persistentSetOf(DeviceSetting.PROMPT_VOLUME),
                promptVolumeLevels = persistentSetOf(0, 1, 2),
            ),
            fields = persistentMapOf(DeviceField.PROMPT_VOLUME to reading),
        )
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                connection = ConnectionState.Connected(id, 42L, profile, ConnectionPhase.READY),
                device = device,
            ),
        )
        val uiSettings = mutableStateOf(UiSettings(interfaceStyle = style))
        val model = FakeOpenRoseModel(state, uiSettings)
        var otherActions = 0
        model.onStartScan = { otherActions++ }
        model.onStopScan = { otherActions++ }
        model.onConnect = { otherActions++ }
        model.onDisconnect = { otherActions++ }
        setContent {
            if (preferences) {
                OpenRoseApp(model, BasicPlatformUi("Test"))
            } else {
                TestPageContent(uiSettings.value.interfaceStyle) {
                    DeviceControlCard(state.value, requireNotNull(state.value.device), model::set)
                }
            }
        }
        val harness = ConnectedHarness(this, state, uiSettings, model.settingsChanges, model.settingWrites, id) {
            otherActions + model.controlSettingsChanges.size
        }
        if (preferences) {
            harness.render()
            harness.clickTab("设置")
            harness.render()
            harness.openPersonalization()
        }
        block(harness)
    }

    private class ConnectedHarness(
        scene: OpenRoseUiTestScene,
        val state: androidx.compose.runtime.MutableState<ControlState>,
        val uiSettings: androidx.compose.runtime.MutableState<UiSettings>,
        val settingsWrites: MutableList<UiSettings>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
        val deviceId: DeviceId,
        private val actionCount: () -> Int,
    ) : OpenRoseUiTestHarness(scene) {
        val otherActions get() = actionCount()

        fun focusedText(): String = nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.Focused) == true
        }?.config?.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
    }
}

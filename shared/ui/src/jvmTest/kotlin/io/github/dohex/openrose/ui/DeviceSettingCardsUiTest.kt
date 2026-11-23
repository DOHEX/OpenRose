@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceSettingCardsUiTest {
    @Test
    fun noiseCycleIsEditableFromTouchControlsWithoutABoundGesture() {
        val profile = ModelProfile.I7_RS_V2
        val capabilities = profile.capabilities()
        val bindings = requireNotNull(capabilities.touchControl).slots.map { TouchBinding(it.side, it.gesture, "none", 0) }
        val flags = mapOf("normal" to true, "anc" to true, "transparency" to false, "wind" to false)
        val state = readyState(
            capabilities,
            persistentMapOf(
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), ""),
                DeviceField.TOUCH_KEYS to FieldReading.Known(FieldValue.TouchKeys(bindings), ""),
                DeviceField.NOISE_CYCLE to FieldReading.Known(FieldValue.NoiseCycle(flags), ""),
            ),
            profile,
        )
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(state, UiSettings(interfaceStyle = style)) { harness ->
                harness.render()
                assertTrue(harness.hasText("降噪模式"))
                assertTrue(harness.hasText("降噪档位"))
                assertFalse(harness.hasText("降噪轮换"))
                harness.clickText("触控操作")
                harness.render(frames = 60)
                assertEquals(1, harness.countText("降噪轮换"))
                harness.scrollToText("降噪轮换")
                harness.clickText("降噪轮换")
                harness.render()
                harness.clickText("通透")
                harness.render()
                assertEquals(
                    listOf(DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(flags + ("transparency" to true))),
                    harness.settingWrites,
                )
            }
        }
    }

    @Test
    fun materialNoiseCycleCanToggleTheLastOptionInLandscape() {
        val profile = ModelProfile.I7_RS_V2
        val flags = mapOf("normal" to true, "anc" to true, "transparency" to false, "wind" to false)
        val state = readyState(
            profile.capabilities(),
            persistentMapOf(DeviceField.NOISE_CYCLE to FieldReading.Known(FieldValue.NoiseCycle(flags), "")),
            profile,
        )
        withOpenRoseAppHarness(state, UiSettings(interfaceStyle = InterfaceStyle.MATERIAL3), width = 780, height = 320) { harness ->
            harness.render()
            harness.clickText("触控操作")
            harness.render(frames = 60)
            harness.scrollToText("降噪轮换")
            harness.clickText("降噪轮换")
            harness.render()
            val closeButton = harness.textBounds("关闭")
            harness.scrollToText("抗风噪")
            assertTrue(harness.clickableBounds("抗风噪").height >= 48f)
            assertEquals(closeButton, harness.textBounds("关闭"))
            harness.clickText("抗风噪")
            assertEquals(listOf(DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(flags + ("wind" to true))), harness.settingWrites)
        }
    }

    @Test
    fun fixedNoiseCycleHasNoModeSelectionRowInTouchControls() {
        val profile = ModelProfile.OPAL_RS_V2
        val state = readyState(profile.capabilities(), profile = profile)
        for (style in InterfaceStyle.entries) {
            withOpenRoseAppHarness(state, UiSettings(interfaceStyle = style)) { harness ->
                harness.render()
                harness.clickText("触控操作")
                harness.render(frames = 60)
                assertTrue(harness.hasText("左右耳手势"))
                assertFalse(harness.hasText("降噪轮换"))
            }
        }
    }

    @Test
    fun touchSlotLabelsAndAudioModesUseCapabilityNames() {
        val labelPairs = listOf("电源键" to "ANC键", "音量−" to "音量＋")
        for ((leftLabel, rightLabel) in labelPairs) {
            assertEquals(
                "${rightLabel}双击绑定为播放 / 暂停",
                displayFieldValue(
                    DeviceField.TOUCH_KEYS,
                    FieldValue.TouchAssignment("right", "double", "play_pause"),
                    touch = touchWithLabels(leftLabel, rightLabel),
                ),
            )
        }
        for (style in InterfaceStyle.entries) {
            for ((leftLabel, rightLabel) in labelPairs) {
                val touch = touchWithLabels(leftLabel, rightLabel)
                val state = readyState(
                    DeviceCapabilities(persistentSetOf(), persistentSetOf(DeviceSetting.TOUCH_BINDING), touchControl = touch),
                )
                val device = requireNotNull(state.device)
                val selectedSide = mutableStateOf("left")
                runOpenRoseUiTest(420, 900) {
                    setContent {
                        ProvideInterfaceStyle(style, dark = false) {
                            TouchControlCard(state, device, null, {}, {}, selectedSide.value, { selectedSide.value = it })
                        }
                    }
                    val harness = OpenRoseUiTestHarness(this)
                    harness.render()
                    assertTrue(harness.hasText(leftLabel))
                    assertTrue(harness.hasText(rightLabel))
                    assertTrue(harness.hasText("${leftLabel}双击"))
                    assertFalse(harness.hasText("左耳"))
                    if (style == InterfaceStyle.MATERIAL3) {
                        assertTrue(harness.hasSelectedTab(leftLabel))
                        harness.clickTab(rightLabel)
                    } else {
                        harness.clickText(rightLabel)
                    }
                    harness.render()
                    if (style == InterfaceStyle.MATERIAL3) assertTrue(harness.hasSelectedTab(rightLabel))
                    assertTrue(harness.hasText("${rightLabel}双击"))
                    System.getenv("OPENROSE_SURFACE_DIR")?.let { directory ->
                        harness.render(frames = 60)
                        val target = File(directory).apply { mkdirs() }
                        val keys = if (leftLabel == "电源键") "headfeel" else "cambrian"
                        ImageIO.write(harness.snapshot(), "png", File(target, "touch-$keys-${style.name.lowercase()}.png"))
                    }
                }
            }
        }
    }

    private fun touchWithLabels(leftLabel: String, rightLabel: String): TouchControlCapabilities {
        val left = TouchSlot("left", "double")
        val right = TouchSlot("right", "double")
        return TouchControlCapabilities(
            persistentSetOf(left, right),
            persistentSetOf("play_pause"),
            persistentMapOf(left to leftLabel, right to rightLabel),
        )
    }

    @Test
    fun tapControlsWriteToTheirIndependentSwitchAndGestureTable() {
        val caps = ModelProfile.ASTRO_X_RS_V2.capabilities().copy(
            touchControl = null,
            writableSettings = persistentSetOf(DeviceSetting.TAP_ENABLED, DeviceSetting.TAP_BINDING),
        )
        val state = readyState(caps, persistentMapOf(DeviceField.TAP_ENABLED to FieldReading.Known(FieldValue.Toggle(false), "00")))
        for (style in InterfaceStyle.entries) {
            val writes = mutableListOf<Pair<DeviceSetting, FieldValue>>()
            runOpenRoseUiTest(420, 900) {
                setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        val card: @Composable () -> Unit = {
                            Column {
                                TouchControlCard(state, requireNotNull(state.device), null, {}, { command -> writes += command.setting to command.value })
                            }
                        }
                        if (style == InterfaceStyle.MIUIX) Scaffold { card() } else card()
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                assertTrue(harness.hasText("轻拍控制"))
                assertFalse(harness.hasText("触控总开关"))
                assertFalse(harness.hasText("左耳单击"))
                harness.clickText("轻拍开关")
                harness.render()
                harness.clickText("右耳三击")
                harness.render()
                harness.clickText(semanticLabel("voice_assistant"))
                harness.render()
                assertEquals(
                    listOf(
                        DeviceSetting.TAP_ENABLED to FieldValue.Toggle(true),
                        DeviceSetting.TAP_BINDING to FieldValue.TouchAssignment("right", "triple", "voice_assistant"),
                    ),
                    writes,
                )
            }
        }
    }

    @Test
    fun spatialContentAppearsAfterTheHeadsetConfirmsAnActiveMode() {
        val capabilities = DeviceCapabilities(
            persistentSetOf(DeviceField.SPATIAL_MODE, DeviceField.SPATIAL_CONTENT),
            persistentSetOf(DeviceSetting.SPATIAL_MODE, DeviceSetting.SPATIAL_CONTENT),
            spatialModes = persistentSetOf("off", "head_tracking", "fixed"),
            spatialContents = persistentSetOf("tv", "music", "movie", "game"),
        )
        for (style in InterfaceStyle.entries) {
            val initial = readyState(
                capabilities,
                persistentMapOf(DeviceField.SPATIAL_MODE to FieldReading.Known(FieldValue.Text("off"), "00")),
            )
            val state = mutableStateOf(initial)
            runOpenRoseUiTest(420, 900) {
                setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        DeviceControlCard(state.value, requireNotNull(state.value.device)) {}
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                assertTrue(harness.hasText("空间模式"))
                assertFalse(harness.hasText("空间音频内容"))
                val device = requireNotNull(initial.device)
                state.value = initial.copy(
                    device = device.copy(diagnostics = device.diagnostics.copy(fields = device.diagnostics.fields + (DeviceField.SPATIAL_MODE to FieldReading.Known(FieldValue.Text("fixed"), "02")))),
                )
                harness.render()
                assertTrue(harness.hasText("空间音频内容"))
                assertTrue(harness.hasText("固定"))
            }
        }
    }

    @Test
    fun spatialAudioSwitchFollowsConfirmedDeviceState() {
        for (style in InterfaceStyle.entries) {
            val initial = readyState(
                DeviceCapabilities(persistentSetOf(), persistentSetOf(DeviceSetting.SPATIAL_AUDIO)),
                persistentMapOf(DeviceField.SPATIAL_AUDIO to FieldReading.Unknown("ff")),
            )
            val device = requireNotNull(initial.device)
            val state = mutableStateOf(initial)
            val writes = mutableListOf<Pair<DeviceSetting, FieldValue>>()
            runOpenRoseUiTest(420, 900) {
                setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        DeviceControlCard(state.value, requireNotNull(state.value.device)) { command ->
                            writes += command.setting to command.value
                        }
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                assertTrue(harness.hasText("空间音频"))
                assertTrue(
                    harness.nodes().any {
                        it.config.contains(SemanticsProperties.Disabled) &&
                            it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
                    },
                    "unknown state disables the switch in $style",
                )

                state.value = state.value.copy(
                    device = device.copy(
                        diagnostics = device.diagnostics.copy(fields = device.diagnostics.fields + (DeviceField.SPATIAL_AUDIO to FieldReading.Known(FieldValue.Toggle(false), "00"))),
                    ),
                )
                harness.render()
                System.getenv("OPENROSE_SURFACE_DIR")?.let { directory ->
                    val target = File(directory).apply { mkdirs() }
                    ImageIO.write(harness.snapshot(), "png", File(target, "spatial-audio-${style.name.lowercase()}.png"))
                }
                harness.clickText("空间音频")
                harness.render()
                assertEquals(listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.SPATIAL_AUDIO to FieldValue.Toggle(true)), writes)
                assertTrue(
                    harness.nodes().any { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off },
                    "request does not change the confirmed value in $style",
                )

                assertSpatialAudioReadback(state, device, harness, style)
            }
        }
    }

    private fun assertSpatialAudioReadback(
        state: MutableState<ControlState>,
        device: DeviceState,
        harness: OpenRoseUiTestHarness,
        style: InterfaceStyle,
    ) {
        state.value = state.value.copy(
            settingOperations = persistentListOf(
                SettingOperation(
                    id = 102,
                    sessionId = device.sessionId,
                    setting = DeviceSetting.SPATIAL_AUDIO,
                    requestedValue = FieldValue.Toggle(true),
                    stage = SettingStage.WRITTEN,
                ),
            ),
        )
        harness.render()
        assertTrue(
            harness.nodes().any {
                it.config.contains(SemanticsProperties.Disabled) &&
                    it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
            },
            "pending request retains the confirmed value in $style",
        )

        state.value = state.value.copy(
            device = device.copy(
                diagnostics = device.diagnostics.copy(fields = device.diagnostics.fields + (DeviceField.SPATIAL_AUDIO to FieldReading.Known(FieldValue.Toggle(true), "01"))),
            ),
            settingOperations = persistentListOf(),
        )
        harness.render()
        assertTrue(
            harness.nodes().any { it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On },
            "readback changes the switch in $style",
        )

        state.value = state.value.copy(
            device = device.copy(capabilities = device.capabilities.copy(writableSettings = persistentSetOf())),
        )
        harness.render()
        assertFalse(harness.hasText("空间音频"))
    }

    private fun readyState(
        capabilities: DeviceCapabilities,
        fields: PersistentMap<DeviceField, FieldReading> = persistentMapOf(),
        profile: ModelProfile = ModelProfile.I5_ZT,
    ): ControlState {
        val id = DeviceId("setting-cards")
        return ControlState(
            platform = PlatformState(permission = PermissionState.GRANTED, transport = TransportAvailability.Available),
            connection = ConnectionState.Connected(id, SESSION_ID, profile, ConnectionPhase.READY),
            device = DeviceState(id, SESSION_ID, profile, capabilities, fields),
        )
    }

    private companion object {
        const val SESSION_ID = 36L
    }
}

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
    fun touchSlotLabelsAndAudioModesUseCapabilityNames() {
        assertEquals("LDAC + LHDC", semanticLabel("ldac_lhdc"))
        for (style in InterfaceStyle.entries) {
            for ((leftLabel, rightLabel) in listOf("电源键" to "ANC键", "音量−" to "音量＋")) {
                val left = TouchSlot("left", "double")
                val right = TouchSlot("right", "double")
                val touch = TouchControlCapabilities(
                    persistentSetOf(left, right),
                    persistentSetOf("play_pause"),
                    persistentMapOf(left to leftLabel, right to rightLabel),
                )
                val state = readyState(
                    DeviceCapabilities(persistentSetOf(), persistentSetOf(DeviceSetting.TOUCH_BINDING), touchControl = touch),
                )
                val device = requireNotNull(state.device)
                val selectedSide = mutableStateOf("left")
                runOpenRoseUiTest(420, 900) {
                    setContent {
                        ProvideInterfaceStyle(style, dark = false) {
                            TouchControlCard(state, device, null, {}, { _, _ -> }, selectedSide.value, { selectedSide.value = it })
                        }
                    }
                    val harness = OpenRoseUiTestHarness(this)
                    harness.render()
                    assertTrue(harness.hasText(leftLabel))
                    assertTrue(harness.hasText(rightLabel))
                    assertTrue(harness.hasText("${leftLabel}双击"))
                    assertFalse(harness.hasText("左耳"))
                    selectedSide.value = "right"
                    harness.render()
                    assertTrue(harness.hasText("${rightLabel}双击"))
                    System.getenv("OPENROSE_SURFACE_DIR")?.let { directory ->
                        harness.render(frames = 60)
                        val target = File(directory).apply { mkdirs() }
                        val keys = if (leftLabel == "电源键") "headfeel" else "cambrian"
                        ImageIO.write(harness.snapshot(), "png", File(target, "touch-$keys-${style.name.lowercase()}.png"))
                    }
                    assertEquals(
                        "${rightLabel}双击绑定为播放 / 暂停",
                        displayFieldValue(
                            DeviceField.TOUCH_KEYS,
                            FieldValue.TouchAssignment("right", "double", "play_pause"),
                            touch = touch,
                        ),
                    )
                }
            }
        }
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
                                TouchControlCard(state, requireNotNull(state.device), null, {}, { setting, value -> writes += setting to value })
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
                        DeviceControlCard(state.value, requireNotNull(state.value.device)) { _, _ -> }
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                assertTrue(harness.hasText("空间模式"))
                assertFalse(harness.hasText("空间音频内容"))
                val device = requireNotNull(initial.device)
                state.value = initial.copy(
                    device = device.copy(fields = device.fields + (DeviceField.SPATIAL_MODE to FieldReading.Known(FieldValue.Text("fixed"), "02"))),
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
                        DeviceControlCard(state.value, requireNotNull(state.value.device)) { setting, value ->
                            writes += setting to value
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
                        fields = device.fields + (DeviceField.SPATIAL_AUDIO to FieldReading.Known(FieldValue.Toggle(false), "00")),
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
                fields = device.fields + (DeviceField.SPATIAL_AUDIO to FieldReading.Known(FieldValue.Toggle(true), "01")),
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
    ): ControlState {
        val profile = ModelProfile.I5_ZT
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

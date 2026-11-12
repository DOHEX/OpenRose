@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerPageUiTest {
    @Test
    fun homeShowsOneEqualizerEntryWithTheConfirmedMode() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(3, 0, 4), onPage = false, withCurve = true) { harness ->
                harness.render()
                assertTrue(harness.hasText("均衡器"), "entry in $style")
                assertTrue(harness.hasText("弱水经典 · POP"), "confirmed mode on the entry in $style")
                assertFalse(harness.hasContentDescription("20 Hz 增益"), "no curve editor on home in $style")
                harness.clickText("均衡器")
                assertEquals(listOf(AppPage.EQUALIZER), harness.navigations, "entry opens the page in $style")
            }
        }
    }

    @Test
    fun homeEntryIsHiddenWithoutAWritablePresetOrCurve() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I5_ZT, listOf(1, 5, 2), onPage = false) { harness ->
                val device = requireNotNull(harness.state.value.device)
                for (capabilities in listOf(
                    device.capabilities.copy(equalizer = null),
                    device.capabilities.copy(equalizer = EqualizerCapabilities(persistentSetOf())),
                    device.capabilities.copy(writableSettings = persistentSetOf()),
                )) {
                    harness.state.value = harness.state.value.copy(device = device.copy(capabilities = capabilities))
                    harness.render()
                    assertFalse(harness.hasText("均衡器"), "$capabilities in $style")
                }
            }
        }
    }

    @Test
    fun aCurveOnlyModelHasTheEntryAndNoPresetRow() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.OPENFEEL_MK2_RS_V2, listOf(1, 0, 0), onPage = false, withCurve = true) { harness ->
                harness.render()
                assertTrue(harness.hasText("均衡器"), "curve-only entry in $style")
            }
            withHarness(style, ModelProfile.OPENFEEL_MK2_RS_V2, listOf(1, 0, 0), onPage = true, withCurve = true) { harness ->
                harness.render()
                assertFalse(harness.hasText("预设"), "no preset row in $style")
                assertTrue(harness.hasContentDescription("20 Hz 增益"), "curve editor in $style")
            }
        }
    }

    @Test
    fun presetsAppearInOrderWithCustomLast() {
        val scenarios = listOf(
            Triple(ModelProfile.I5_ZT, listOf(1, 5, 2), listOf("弱水经典 · POP", "日系柔情 · HIFI", "乐器大师 · ROCK", "清新空灵 · LIGHT")),
            Triple(ModelProfile.I7_RS_V2, listOf(1, 5, 2), listOf("弱水经典 · POP", "原声再现 · HIFI", "古典乐器 · ROCK", "日系甜美 · LIGHT", "欧美流行 · EAP")),
        )
        for (style in InterfaceStyle.entries) {
            for ((profile, firmware, labels) in scenarios) {
                withHarness(style, profile, firmware, onPage = true) { harness ->
                    harness.render()
                    assertEquals(profile == ModelProfile.I7_RS_V2, harness.hasText("欧美流行 · EAP"))
                    val positions = labels.map { label ->
                        harness.nodes().last { node ->
                            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
                        }.boundsInRoot.let { it.top to it.left }
                    }
                    assertTrue(
                        positions.zipWithNext().all { (a, b) -> a.first < b.first || (a.first == b.first && a.second < b.second) },
                        "preset order in $style",
                    )
                    assertFalse(harness.hasText("自定义"), "no custom choice without a writable curve in $style")
                }
            }
            withHarness(style, ModelProfile.I7_RS_V2, listOf(3, 0, 4), onPage = true, withCurve = true) { harness ->
                harness.render()
                assertTrue(harness.hasText("自定义"), "custom choice with a writable curve in $style")
            }
        }
    }

    @Test
    fun aPresetChoiceShowsUntilItsWriteEndsThenTheDeviceDecides() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), onPage = true) { harness ->
                harness.render()
                assertTrue(harness.hasSelectedText("弱水经典 · POP"))
                harness.clickText("原声再现 · HIFI")
                harness.render()
                assertEquals(listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.EQ to FieldValue.Text("hifi")), harness.writes)
                assertTrue(harness.hasSelectedText("原声再现 · HIFI"), "the choice shows while it is written in $style")

                harness.replies.single().complete(OperationResult.Failure(ControlError(ControlErrorCode.NOT_APPLIED, "设备状态与请求不一致")))
                harness.render()
                assertTrue(harness.hasSelectedText("弱水经典 · POP"), "a write the device did not apply falls back in $style")

                harness.clickText("弱水经典 · POP")
                harness.render()
                assertEquals(1, harness.writes.size, "the confirmed preset is not sent again in $style")
            }
        }
    }

    @Test
    fun choicesWaitForAReadySessionButNotForOtherPendingSettings() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(3, 0, 4), onPage = true, withCurve = true) { harness ->
                val ready = harness.state.value
                val connection = ready.connection as ConnectionState.Connected
                harness.state.value = ready.copy(connection = connection.copy(phase = ConnectionPhase.INITIALIZING))
                harness.render()
                assertTrue(harness.hasDisabledText("原声再现 · HIFI"), "initializing in $style")
                assertFalse(harness.setProgress("20 Hz 增益", 5f), "bands refuse changes while initializing in $style")

                harness.state.value = ready.copy(
                    settingOperations = persistentListOf(
                        SettingOperation(1, SESSION_ID, DeviceSetting.NOISE_MODE, FieldValue.Text("anc"), SettingStage.SUBMITTED),
                    ),
                )
                harness.render()
                harness.clickText("原声再现 · HIFI")
                harness.render()
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.EQ to FieldValue.Text("hifi")),
                    harness.writes,
                    "another setting in flight does not block the page in $style",
                )
            }
        }
    }

    @Test
    fun aBandChangeWritesTheWholeCurveFromTheConfirmedOne() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(3, 0, 4), onPage = true, withCurve = true) { harness ->
                harness.render()
                assertTrue(harness.hasText("自定义曲线"), "curve editor in $style")
                assertTrue(harness.setProgress("20 Hz 增益", 5f))
                harness.render()
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.CUSTOM_EQ to FieldValue.IntegerList(CURVE.replacingAt(0, 5))),
                    harness.writes,
                    "in $style",
                )
                assertTrue(harness.hasSelectedText("自定义"), "editing the curve selects custom in $style")
            }
        }
    }

    private fun withHarness(
        style: InterfaceStyle,
        profile: ModelProfile,
        parts: List<Int>,
        onPage: Boolean,
        withCurve: Boolean = false,
        block: suspend (EqHarness) -> Unit,
    ) {
        val id = DeviceId("eq-test")
        val firmware = FieldValue.Firmware(parts, parts.joinToString(""))
        val writable = if (withCurve) persistentSetOf(DeviceSetting.EQ, DeviceSetting.CUSTOM_EQ) else persistentSetOf(DeviceSetting.EQ)
        val fields = persistentMapOf<DeviceField, FieldReading>(DeviceField.EQ to FieldReading.Known(FieldValue.Text("pop"), "00"))
        val device = DeviceState(
            id,
            SESSION_ID,
            profile,
            DeviceCapabilities(
                readableFields = persistentSetOf(DeviceField.EQ, DeviceField.CUSTOM_EQ),
                writableSettings = writable,
                equalizer = profile.capabilities(firmware).equalizer,
            ),
            fields = if (withCurve) fields + (DeviceField.CUSTOM_EQ to FieldReading.Known(FieldValue.IntegerList(CURVE), "")) else fields,
        )
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                connection = ConnectionState.Connected(id, SESSION_ID, profile, ConnectionPhase.READY),
                device = device,
            ),
        )
        val uiSettings = mutableStateOf(UiSettings(interfaceStyle = style))
        val writes = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val replies = mutableListOf<CompletableDeferred<OperationResult>>()
        val navigations = mutableListOf<AppPage>()
        val model = FakeOpenRoseModel(state, uiSettings).apply {
            onSetInSession = { _, setting, value ->
                writes += setting to value
                CompletableDeferred<OperationResult>().also { replies += it }.await()
            }
        }
        runOpenRoseUiTest(420, 1200) {
            setContent {
                TestPageContent(uiSettings.value.interfaceStyle) {
                    if (onPage) {
                        val scope = rememberCoroutineScope()
                        val editor = remember { EqualizerEditor(scope, model::setInSession) }
                        val sessionId = state.value.readySession?.sessionId
                        LaunchedEffect(sessionId) { editor.bind(sessionId) }
                        EqualizerPage(state.value, requireNotNull(state.value.device), editor)
                    } else {
                        HeadphonesPage(model, BasicPlatformUi("Test"), { navigations += it })
                    }
                }
            }
            block(EqHarness(this, state, writes, replies, navigations))
        }
    }

    private class EqHarness(
        scene: OpenRoseUiTestScene,
        val state: MutableState<ControlState>,
        val writes: List<Pair<DeviceSetting, FieldValue>>,
        val replies: List<CompletableDeferred<OperationResult>>,
        val navigations: List<AppPage>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 42L
        val CURVE = persistentListOf(-2, 0, 1, 3, 0, -1, 2, 0, 4, -3)
    }
}

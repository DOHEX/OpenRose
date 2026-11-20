@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerPageUiTest {
    @Test
    fun aModelWithAWritableCurveOpensThePageFromHome() {
        for (style in InterfaceStyle.entries) {
            for ((profile, firmware, confirmed) in listOf(
                Triple(ModelProfile.I7_RS_V2, listOf(1, 5, 2), "pop" to "弱水经典 · POP"),
                Triple(ModelProfile.HEADFEEL_ZT, listOf(1, 3, 0), "pop" to "POP"),
                Triple(ModelProfile.OPENFEEL_MK2_RS_V2, listOf(1, 0, 0), CUSTOM to "自定义"),
            )) {
                withHarness(style, profile, firmware, Surface.HOME, mode = confirmed.first) { harness ->
                    harness.render()
                    assertTrue(harness.hasText(confirmed.second), "the confirmed mode on the entry for $profile in $style")
                    assertFalse(harness.hasContentDescription("20 Hz 增益"), "no curve editor on home for $profile in $style")
                    harness.clickText("均衡器")
                    harness.render()
                    assertEquals(listOf(AppPage.EQUALIZER), harness.navigations, "$profile opens the page in $style")
                    assertTrue(harness.homeWrites.isEmpty(), "opening the page writes nothing for $profile in $style")
                }
            }
        }
    }

    @Test
    fun aPresetOnlyModelChoosesItsPresetOnHome() {
        val scenarios = listOf(
            Triple(ModelProfile.I5_ZT, listOf(1, 5, 2), listOf("弱水经典 · POP", "日系柔情 · HIFI", "乐器大师 · ROCK", "清新空灵 · LIGHT")),
            Triple(ModelProfile.HEADFEEL_ZT, listOf(1, 2, 9), listOf("POP", "HIFI", "ROCK")),
        )
        for (style in InterfaceStyle.entries) {
            for ((profile, firmware, labels) in scenarios) {
                withHarness(style, profile, firmware, Surface.HOME) { harness ->
                    harness.render()
                    assertTrue(harness.hasText(labels[0]), "the confirmed preset shows on the entry for $profile in $style")
                    harness.clickText("均衡器")
                    harness.render()
                    assertTrue(harness.navigations.isEmpty(), "a preset-only entry does not open the page for $profile in $style")
                    assertTrue(harness.hasSelectedText(labels[0]), "the confirmed preset is selected for $profile in $style")
                    val tops = labels.map { label -> harness.lastTextBounds(label).top }
                    assertTrue(tops.zipWithNext().all { (a, b) -> a < b }, "presets in order for $profile in $style: $tops")
                    assertFalse(harness.hasText("自定义"), "no custom choice on home for $profile in $style")

                    harness.clickText(labels[1])
                    harness.render()
                    assertEquals(
                        listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.EQ to FieldValue.Text("hifi")),
                        harness.homeWrites,
                        "choosing writes the preset for $profile in $style",
                    )
                    assertTrue(harness.navigations.isEmpty())
                    assertTrue(harness.hasText(labels[0]), "the entry keeps the confirmed preset until the device confirms for $profile in $style")
                    assertFalse(harness.hasText(labels[1]), "the requested preset is not shown as confirmed for $profile in $style")

                    harness.clickText("均衡器")
                    harness.render()
                    harness.clickText(labels[0])
                    harness.render()
                    assertEquals(1, harness.homeWrites.size, "the confirmed preset is not sent again for $profile in $style")
                }
            }
        }
    }

    @Test
    fun aPresetOnlyEntryWaitsForAReadySessionWithNothingInFlight() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I5_ZT, listOf(1, 5, 2), Surface.HOME) { harness ->
                harness.render()
                assertTrue(harness.hasTappableText("均衡器"), "the entry takes taps once ready in $style")
                val ready = harness.state.value
                val connection = ready.connection as ConnectionState.Connected
                for (blocked in listOf(
                    ready.copy(connection = connection.copy(phase = ConnectionPhase.INITIALIZING)),
                    ready.copy(
                        settingOperations = persistentListOf(
                            SettingOperation(1, SESSION_ID, DeviceSetting.NOISE_MODE, FieldValue.Text("anc"), SettingStage.SUBMITTED),
                        ),
                    ),
                )) {
                    harness.state.value = blocked
                    harness.render()
                    assertFalse(harness.hasTappableText("均衡器"), "the entry takes no taps in $style")
                }
            }
        }
    }

    @Test
    fun homeEntryIsHiddenWithoutAWritablePresetOrCurve() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I5_ZT, listOf(1, 5, 2), Surface.HOME) { harness ->
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
    fun homeEntryFollowsTheFirmwareGatedCurve() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.HEADFEEL_ZT, listOf(1, 2, 9), Surface.HOME) { harness ->
                harness.render()
                harness.clickText("均衡器")
                harness.render()
                assertTrue(harness.navigations.isEmpty(), "before firmware 130 the entry chooses a preset in $style")
                harness.clickText("POP")
                harness.render()

                harness.state.value = harness.withFirmware(listOf(1, 3, 0))
                harness.render()
                harness.clickText("均衡器")
                harness.render()
                assertEquals(listOf(AppPage.EQUALIZER), harness.navigations, "from firmware 130 the entry opens the page in $style")
            }
        }
    }

    @Test
    fun modesAreOneVerticalListInPresetOrderWithCustomLast() {
        val labels = listOf("弱水经典 · POP", "原声再现 · HIFI", "古典乐器 · ROCK", "日系甜美 · LIGHT", "欧美流行 · EAP", "自定义")
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE) { harness ->
                harness.render()
                assertTrue(harness.hasText("模式"), "mode group in $style")
                val bounds = labels.map { label ->
                    assertTrue(harness.hasRadioText(label), "$label is a radio option in $style")
                    harness.lastTextBounds(label)
                }
                assertTrue(bounds.zipWithNext().all { (a, b) -> a.top < b.top }, "one option per line, in order, in $style")
                assertTrue(bounds.all { it.left == bounds[0].left }, "options share one column in $style")
                assertTrue(harness.hasSelectedText("弱水经典 · POP"), "the confirmed mode is selected in $style")
            }
            withHarness(style, ModelProfile.OPENFEEL_MK2_RS_V2, listOf(1, 0, 0), Surface.PAGE, mode = null) { harness ->
                harness.render()
                assertFalse(harness.hasText("模式"), "no mode group without presets in $style")
                assertTrue(harness.hasText("自定义曲线"), "the curve card always shows without presets in $style")
                assertTrue(harness.hasContentDescription("20 Hz 增益"), "curve editor without presets in $style")
            }
        }
    }

    @Test
    fun aPresetChoiceShowsUntilItsWriteEndsThenTheDeviceDecides() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE) { harness ->
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
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE, mode = CUSTOM) { harness ->
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
    fun aPresetModeHasNoCurveAndCustomWritesTheCurrentCurve() {
        for (style in InterfaceStyle.entries) {
            for ((curve, written) in listOf(CURVE to CURVE, null to FLAT)) {
                withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE, curve = curve) { harness ->
                    harness.render()
                    assertFalse(harness.hasText("自定义曲线"), "no curve card in a preset mode in $style")
                    assertFalse(harness.hasContentDescription("20 Hz 增益"), "no curve editor in a preset mode in $style")
                    assertFalse(harness.hasText("重置"), "no reset in a preset mode in $style")

                    harness.clickText("自定义")
                    harness.render()
                    assertEquals(
                        listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.CUSTOM_EQ to FieldValue.IntegerList(written)),
                        harness.writes,
                        "custom writes the current curve in $style",
                    )
                    assertTrue(harness.hasSelectedText("自定义"), "custom shows while it is written in $style")
                    assertTrue(harness.hasContentDescription("20 Hz 增益"), "the editor opens with custom in $style")
                    assertEquals(
                        curve == null,
                        harness.hasText("尚未读取耳机上的曲线，以下为平直曲线"),
                        "only an unread curve is explained in $style",
                    )
                }
            }
        }
    }

    @Test
    fun aBandChangeWritesTheWholeCurve() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE, mode = CUSTOM) { harness ->
                harness.render()
                assertTrue(harness.hasSelectedText("自定义"), "custom mode in $style")
                assertTrue(harness.setProgress("20 Hz 增益", 5f))
                harness.render()
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.CUSTOM_EQ to FieldValue.IntegerList(CURVE.replacingAt(0, 5))),
                    harness.writes,
                    "in $style",
                )
            }
        }
    }

    @Test
    fun resetWritesTheFlatCurveUnlessTheCurveIsFlat() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE, mode = CUSTOM) { harness ->
                harness.render()
                harness.clickText("重置")
                harness.render()
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.CUSTOM_EQ to FieldValue.IntegerList(FLAT)),
                    harness.writes,
                    "reset writes the flat curve in $style",
                )
                harness.replies.single().complete(OperationResult.Success)
                harness.state.value = harness.withCurve(FLAT)
                harness.render()
                assertTrue(harness.hasDisabledText("重置"), "reset is disabled once the curve is flat in $style")
            }
        }
    }

    @Test
    fun rightToLeftCurveDrawsTheHighThumbUnderItsOwnFrequencyAndReadsTheGainWithoutAUnit() {
        val custom = CustomEqualizerCapabilities(persistentListOf(20, 1000), -6, 6)
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(400, 240) {
                var thumbColor = 0
                val writes = mutableListOf<List<Int>>()
                setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        ProvideInterfaceStyle(style, dark = false) {
                            thumbColor = appColor(AppColorRole.Primary).toArgb()
                            EqualizerBands(listOf(6, -6), custom, enabled = true, active = true, onCommit = { writes += it })
                        }
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                val first = harness.nodes().single { "20 Hz 增益" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                val band = first.boundsInRoot
                assertTrue(band.center.x > harness.textBounds("1k").center.x)
                assertEquals("+6", first.config.getOrNull(SemanticsProperties.StateDescription))
                val frame = harness.snapshot()
                val centerX = band.center.x.toInt()
                val top = band.top.toInt()
                assertTrue((top until (top + band.height.toInt() / 4)).any { frame.getRGB(centerX, it) == thumbColor })
                harness.setProgress("20 Hz 增益", 3f)
                assertEquals(listOf(3, -6), writes.single())
            }
        }
    }

    @Test
    fun theCurveHasAGainScaleAndSignedBandValues() {
        val custom = CustomEqualizerCapabilities(persistentListOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000), -12, 10)
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.PAGE, mode = CUSTOM, custom = custom) { harness ->
                harness.render()
                val top = harness.lastTextBounds("+10")
                val bottom = harness.lastTextBounds("−12")
                val firstBand = harness.lastTextBounds("20")
                assertTrue(top.bottom < bottom.top, "the model's maximum sits above its minimum in $style")
                assertTrue(top.right <= firstBand.left && bottom.right <= firstBand.left, "the scale sits left of the bands in $style")
                val zero = harness.allTextBounds("0").single { it.right <= firstBand.left }
                assertTrue(top.bottom <= zero.top && zero.bottom <= bottom.top, "the scale marks zero between its bounds in $style")
                for (value in listOf("+3", "−2", "+4", "−3")) {
                    assertTrue(harness.hasText(value), "band value $value in $style")
                }
                assertEquals(
                    CURVE.count { it == 0 },
                    harness.allTextBounds("0").count { it.left >= firstBand.left },
                    "a flat band reads 0 in $style",
                )
                assertFalse(harness.hasText("-2"), "negative values use the minus sign in $style")
            }
        }
    }

    @Test
    fun losingTheCurveOnThePageDropsTheCurveCardAndStays() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.APP, mode = CUSTOM) { harness ->
                harness.render()
                harness.clickText("均衡器")
                harness.render(frames = 30)
                assertTrue(harness.hasText("自定义曲线"), "curve card on the page in $style")

                val device = requireNotNull(harness.state.value.device)
                val presetsOnly = device.capabilities.copy(writableSettings = persistentSetOf(DeviceSetting.EQ))
                harness.state.value = harness.state.value.copy(device = device.copy(capabilities = presetsOnly))
                harness.render(frames = 30)
                assertFalse(harness.hasText("自定义曲线"), "the curve card goes with the curve in $style")
                assertFalse(harness.hasContentDescription("20 Hz 增益"), "no curve editor without a writable curve in $style")
                assertFalse(harness.hasText("自定义"), "no custom mode without a writable curve in $style")
                assertTrue(harness.hasRadioText("弱水经典 · POP"), "the page stays open with its modes in $style")
                assertFalse(harness.hasText("设备详情"), "the page does not return home in $style")
            }
        }
    }

    @Test
    fun anI7CustomCurveFitsOnOnePhoneScreen() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, ModelProfile.I7_RS_V2, listOf(1, 5, 2), Surface.APP, mode = CUSTOM, width = PHONE_WIDTH, height = PHONE_HEIGHT) { harness ->
                harness.render()
                harness.clickText("均衡器")
                harness.render(frames = 60)
                val overflow = harness.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                    .maxOf { it.maxValue() }
                assertEquals(0f, overflow, "the page has nothing to scroll at $PHONE_WIDTH×$PHONE_HEIGHT in $style")
                val title = harness.lastTextBounds("自定义曲线")
                val reset = harness.lastTextBounds("重置")
                assertTrue(
                    abs(title.center.y - reset.center.y) <= 1f,
                    "reset sits on the title's line in $style: ${title.center.y} vs ${reset.center.y}",
                )
                System.getenv("OPENROSE_SURFACE_DIR")?.let { directory ->
                    val target = File(directory).apply { mkdirs() }
                    ImageIO.write(harness.snapshot(), "png", File(target, "equalizer-i7-custom-${style.name.lowercase()}.png"))
                }
            }
        }
    }

    private enum class Surface { HOME, PAGE, APP }

    private fun withHarness(
        style: InterfaceStyle,
        profile: ModelProfile,
        parts: List<Int>,
        surface: Surface,
        mode: String? = "pop",
        curve: List<Int>? = CURVE,
        custom: CustomEqualizerCapabilities? = null,
        width: Int = 420,
        height: Int = 1200,
        block: suspend (EqHarness) -> Unit,
    ) {
        val id = DeviceId("eq-test")
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                connection = ConnectionState.Connected(id, SESSION_ID, profile, ConnectionPhase.READY),
                device = DeviceState(
                    id,
                    SESSION_ID,
                    profile,
                    capabilitiesFor(profile, parts, custom),
                    fields = buildMap<DeviceField, FieldReading> {
                        mode?.let { put(DeviceField.EQ, FieldReading.Known(FieldValue.Text(it), "00")) }
                        curve?.let { put(DeviceField.CUSTOM_EQ, FieldReading.Known(FieldValue.IntegerList(it), "")) }
                    }.toPersistentMap(),
                ),
            ),
        )
        val uiSettings = mutableStateOf(UiSettings(interfaceStyle = style))
        val writes = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val replies = mutableListOf<CompletableDeferred<OperationResult>>()
        val homeWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val navigations = mutableListOf<AppPage>()
        val model = FakeOpenRoseModel(state, uiSettings).apply {
            onSet = { setting, value -> homeWrites += setting to value }
            onSetInSession = { _, setting, value ->
                writes += setting to value
                CompletableDeferred<OperationResult>().also { replies += it }.await()
            }
        }
        runOpenRoseUiTest(width, height) {
            setContent {
                when (surface) {
                    Surface.APP -> OpenRoseApp(model, BasicPlatformUi("Test"))

                    Surface.HOME -> TestPageContent(uiSettings.value.interfaceStyle) {
                        HeadphonesPage(model, BasicPlatformUi("Test"), { navigations += it })
                    }

                    Surface.PAGE -> TestPageContent(uiSettings.value.interfaceStyle) {
                        val scope = rememberCoroutineScope()
                        val editor = remember { EqualizerEditor(scope, model::setInSession) }
                        val sessionId = state.value.readySession?.sessionId
                        LaunchedEffect(sessionId) { editor.bind(sessionId) }
                        EqualizerPage(state.value, requireNotNull(state.value.device), editor)
                    }
                }
            }
            block(EqHarness(this, state, writes, homeWrites, replies, navigations, profile, custom))
        }
    }

    /** [writes] go through the equalizer page's session writes; [homeWrites] through the home rows' settings. */
    private class EqHarness(
        scene: OpenRoseUiTestScene,
        val state: MutableState<ControlState>,
        val writes: List<Pair<DeviceSetting, FieldValue>>,
        val homeWrites: List<Pair<DeviceSetting, FieldValue>>,
        val replies: List<CompletableDeferred<OperationResult>>,
        val navigations: List<AppPage>,
        private val profile: ModelProfile,
        private val custom: CustomEqualizerCapabilities?,
    ) : OpenRoseUiTestHarness(scene) {
        /** The last match is the topmost: popups and dialogs come after the window. */
        fun lastTextBounds(text: String) = allTextBounds(text).last()

        fun withFirmware(parts: List<Int>): ControlState {
            val device = requireNotNull(state.value.device)
            return state.value.copy(device = device.copy(capabilities = capabilitiesFor(profile, parts, custom)))
        }

        fun withCurve(curve: List<Int>): ControlState {
            val device = requireNotNull(state.value.device)
            return state.value.copy(
                device = device.copy(
                    fields = device.fields + (DeviceField.CUSTOM_EQ to FieldReading.Known(FieldValue.IntegerList(curve), "")),
                ),
            )
        }
    }

    private companion object {
        const val SESSION_ID = 42L
        const val CUSTOM = "custom"

        /** The sandbox's phone preview, a common portrait phone in dp. */
        const val PHONE_WIDTH = 360
        const val PHONE_HEIGHT = 780
        val CURVE = persistentListOf(-2, 0, 1, 3, 0, -1, 2, 0, 4, -3)
        val FLAT = List(10) { 0 }

        /** The model's equalizer as its firmware decides, readable and writable as far as the model allows. */
        fun capabilitiesFor(profile: ModelProfile, parts: List<Int>, custom: CustomEqualizerCapabilities?): DeviceCapabilities {
            val model = profile.capabilities(FieldValue.Firmware(parts, parts.joinToString("")))
            val equalizer = model.equalizer?.let { eq -> custom?.let { eq.copy(custom = it) } ?: eq }
            return DeviceCapabilities(
                readableFields = persistentSetOf(DeviceField.EQ, DeviceField.CUSTOM_EQ),
                writableSettings = model.writableSettings.filter { it == DeviceSetting.EQ || it == DeviceSetting.CUSTOM_EQ }.toPersistentSet(),
                equalizer = equalizer,
            )
        }
    }
}

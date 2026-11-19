@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickPanelUiTest {
    private val bonded = DeviceCandidate(
        deviceId = DeviceId("rose-1"),
        name = "Device rose-1",
        rssi = null,
        profile = ModelProfile.I5_ZT,
        manufacturerDataHex = "",
    )

    @Test
    fun equalizerChoiceWritesThePresetAndIsSelectedOnlyOnceTheHeadsetConfirms() {
        val base = connectedI7State()
        val presets = requireNotNull(base.device).writableEqualizerPresets()
        assertTrue(presets.size >= 2, "the fixture model offers at least two presets")
        val (current, other) = presets
        for (style in InterfaceStyle.entries) {
            val state = withFields(base, DeviceField.EQ to known(FieldValue.Text(current)))
            withQuickPanel(state, style) { panel ->
                panel.render()
                assertTrue(panel.isTagSelected(quickEqualizerTag(current)), "confirmed preset selected in $style")
                assertFalse(panel.isTagSelected(quickEqualizerTag(other)), "other preset not selected in $style")

                panel.clickTag(quickEqualizerTag(current))
                assertEquals(emptyList<Pair<DeviceSetting, FieldValue>>(), panel.model.settingWrites, "confirmed preset in $style")

                panel.clickTag(quickEqualizerTag(other))
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.EQ to FieldValue.Text(other)),
                    panel.model.settingWrites,
                    "write in $style",
                )

                panel.render()
                assertFalse(panel.isTagSelected(quickEqualizerTag(other)), "not optimistic in $style")
                assertTrue(panel.isTagSelected(quickEqualizerTag(current)), "still the confirmed preset in $style")

                panel.model.mutableState.value = withFields(base, DeviceField.EQ to known(FieldValue.Text(other)))
                panel.render()
                assertTrue(panel.isTagSelected(quickEqualizerTag(other)), "confirmed after read-back in $style")
                assertFalse(panel.isTagSelected(quickEqualizerTag(current)), "previous preset released in $style")
                assertEquals(0, panel.dismissCount, "the panel stays open after a change in $style")
            }
        }
    }

    @Test
    fun equalizerChoicesAreDisabledWhileASettingIsPending() {
        val base = connectedI7State()
        val device = requireNotNull(base.device)
        val current = device.writableEqualizerPresets().first()
        val pending = withFields(base, DeviceField.EQ to known(FieldValue.Text(current))).copy(
            settingOperations = listOf(
                SettingOperation(
                    id = 1L,
                    sessionId = device.sessionId,
                    setting = DeviceSetting.EQ,
                    requestedValue = FieldValue.Text(device.writableEqualizerPresets().last()),
                    stage = SettingStage.WRITTEN,
                ),
            ).toPersistentList(),
        )
        for (style in InterfaceStyle.entries) {
            withQuickPanel(pending, style) { panel ->
                panel.render()
                for (preset in device.writableEqualizerPresets()) {
                    assertTrue(panel.isTagDisabled(quickEqualizerTag(preset)), "$preset disabled while pending in $style")
                }
                assertTrue(panel.isTagSelected(quickEqualizerTag(current)), "only the read-back is selected in $style")
                assertTrue(panel.hasText("正在处理设置，等待设备确认"), "pending state shown in $style")
            }
        }
    }

    @Test
    fun gameModeSwitchWritesTheOppositeOfTheConfirmedValue() {
        for (style in InterfaceStyle.entries) {
            val state = withFields(connectedI7State(), DeviceField.GAME_MODE to known(FieldValue.Toggle(false)))
            withQuickPanel(state, style) { panel ->
                panel.render()
                panel.clickText("游戏模式")
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.GAME_MODE to FieldValue.Toggle(true)),
                    panel.model.settingWrites,
                    "game mode in $style",
                )
            }
        }
    }

    @Test
    fun controlsTheModelCannotWriteAreHidden() {
        val base = connectedI7State()
        val device = requireNotNull(base.device)
        val limited = base.copy(
            device = device.copy(
                capabilities = device.capabilities.copy(
                    writableSettings = DeviceSetting.entries
                        .filterNot { it == DeviceSetting.EQ || it == DeviceSetting.GAME_MODE }
                        .toPersistentSet(),
                ),
            ),
        )
        for (style in InterfaceStyle.entries) {
            withQuickPanel(limited, style) { panel ->
                panel.render()
                assertTrue(panel.hasText("降噪模式"), "noise mode stays in $style")
                assertFalse(panel.hasText("均衡器"), "no equalizer in $style")
                assertFalse(panel.hasTagWithPrefix("quick-eq-"), "no equalizer choices in $style")
                assertFalse(panel.hasText("游戏模式"), "no game mode in $style")
            }
        }
    }

    @Test
    fun theTrayOnlyControlsAreNotOffered() {
        for (style in InterfaceStyle.entries) {
            withQuickPanel(connectedI7State(), style) { panel ->
                panel.render()
                assertFalse(panel.hasText("降噪档位"), "no ANC level in $style")
                assertFalse(panel.hasText("多点连接"), "no multipoint in $style")
                assertFalse(panel.hasText("触控操作"), "no touch bindings in $style")
            }
        }
    }

    @Test
    fun disconnectedPanelOffersReconnectAndOpeningTheApp() {
        for (style in InterfaceStyle.entries) {
            val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(bonded))
            withQuickPanel(state, style) { panel ->
                panel.render()
                assertTrue(panel.hasText("Device rose-1 · 未连接"), "status in $style")
                assertFalse(panel.hasText("降噪模式"), "no controls in $style")
                panel.clickText("重新连接")
                assertEquals(listOf(bonded.deviceId), panel.model.connectRequests, "reconnect in $style")
                panel.clickText("打开 OpenRose")
                assertEquals(1, panel.openAppCount, "open app in $style")
            }
        }
    }

    @Test
    fun connectingPanelCanCancelAndWithoutADeviceAddHeadphonesOpensTheApp() {
        for (style in InterfaceStyle.entries) {
            val connecting = disconnectedAndroidState().copy(
                bondedDevices = persistentListOf(bonded),
                connection = ConnectionState.Connecting(bonded.deviceId, 1L, ConnectionPhase.CONNECTING),
            )
            withQuickPanel(connecting, style) { panel ->
                panel.render()
                assertFalse(panel.hasText("重新连接"), "no reconnect while connecting in $style")
                panel.clickText("取消连接")
                assertEquals(1, panel.model.disconnectCount, "cancel in $style")
            }
            withQuickPanel(disconnectedAndroidState(), style) { panel ->
                panel.render()
                panel.clickText("添加耳机")
                assertEquals(1L, panel.model.homeNavigationRequests.value, "showHeadphones in $style")
                assertEquals(1, panel.openAppCount, "open app in $style")
            }
        }
    }

    @Test
    fun onlyTheScrimDismissesThePanel() {
        for (style in InterfaceStyle.entries) {
            withQuickPanel(connectedI7State(), style) { panel ->
                panel.render()
                panel.clickText("打开 OpenRose")
                assertEquals(0, panel.dismissCount, "the host decides what opening the app does in $style")
                assertEquals(1, panel.openAppCount, "open app in $style")

                panel.clickTag(QUICK_PANEL_SCRIM_TAG)
                assertEquals(0, panel.dismissCount, "the sheet animates out before dismissing in $style")
                panel.clickTag(QUICK_PANEL_SCRIM_TAG)
                panel.advanceTimeBy(1_000)
                assertEquals(1, panel.dismissCount, "scrim in $style")
            }
        }
    }

    @Test
    fun aShortHandleDragFollowsTheFingerAndSpringsBack() {
        for (style in InterfaceStyle.entries) {
            withQuickPanel(connectedI7State(), style) { panel ->
                panel.render(60)
                val rest = panel.tagBounds(QUICK_PANEL_HANDLE_TAG)
                panel.pressAndDrag(rest.center, Offset(0f, 60f))
                panel.render()
                val dragged = panel.tagBounds(QUICK_PANEL_HANDLE_TAG)
                assertTrue(dragged.top > rest.top + 20f, "the sheet follows the drag in $style: $rest -> $dragged")

                panel.releaseTouch()
                panel.advanceTimeBy(1_000)
                assertEquals(rest.top, panel.tagBounds(QUICK_PANEL_HANDLE_TAG).top, 1f, "springs back in $style")
                assertEquals(0, panel.dismissCount, "a short drag keeps the panel in $style")
            }
        }
    }

    @Test
    fun anUpwardFlingNeverLiftsTheSheetAboveRest() {
        for (style in InterfaceStyle.entries) {
            withQuickPanel(connectedI7State(), style) { panel ->
                panel.render(60)
                val rest = panel.tagBounds(QUICK_PANEL_HANDLE_TAG)
                panel.pressAndDrag(rest.center, Offset(0f, -200f))
                panel.releaseTouch()
                repeat(40) {
                    panel.advanceTimeBy(16)
                    val top = panel.tagBounds(QUICK_PANEL_HANDLE_TAG).top
                    assertTrue(top >= rest.top - 0.5f, "frame $it lifted the sheet in $style: ${rest.top} -> $top")
                }
                assertEquals(0, panel.dismissCount, "an upward fling keeps the panel in $style")
            }
        }
    }

    @Test
    fun aLongHandleDragDismissesOnce() {
        for (style in InterfaceStyle.entries) {
            withQuickPanel(connectedI7State(), style) { panel ->
                panel.render()
                val rest = panel.tagBounds(QUICK_PANEL_HANDLE_TAG)
                panel.pressAndDrag(rest.center, Offset(0f, 1000f - rest.center.y - 1f))
                panel.releaseTouch()
                panel.advanceTimeBy(1_000)
                assertEquals(1, panel.dismissCount, "a long drag dismisses in $style")
            }
        }
    }

    @Test
    fun tileSummaryListsKnownEarbudsOnlyAndNeverTheCase() {
        val base = connectedI7State()
        assertEquals(QuickTileSummary(active = true, subtitle = "左耳 90% · 右耳 90%"), quickTileSummary(base))

        val oneSide = withFields(
            base,
            DeviceField.LEFT_BATTERY to known(FieldValue.Number(80)),
            DeviceField.RIGHT_BATTERY to FieldReading.Unknown("ff"),
        )
        assertEquals(QuickTileSummary(active = true, subtitle = "左耳 80%"), quickTileSummary(oneSide))

        val outOfRange = withFields(
            base,
            DeviceField.LEFT_BATTERY to known(FieldValue.Number(255)),
            DeviceField.RIGHT_BATTERY to FieldReading.Loading,
        )
        assertEquals(QuickTileSummary(active = true, subtitle = "已连接"), quickTileSummary(outOfRange))

        val caseOnly = withFields(
            base,
            DeviceField.LEFT_BATTERY to FieldReading.NotProvided(""),
            DeviceField.RIGHT_BATTERY to FieldReading.NotProvided(""),
            DeviceField.CASE_BATTERY to known(FieldValue.Number(70)),
        )
        assertEquals(QuickTileSummary(active = true, subtitle = "已连接"), quickTileSummary(caseOnly))
    }

    @Test
    fun tileSummaryOfASingleUnitUsesItsOneReading() {
        val base = connectedI7State()
        val device = requireNotNull(base.device)
        val profile = ModelProfile.SWIFT2_RS_V2
        assertEquals(BatteryLayout.SINGLE_UNIT, profile.batteryLayout)
        val single = base.copy(
            connection = ConnectionState.Connected(device.deviceId, device.sessionId, profile, ConnectionPhase.READY),
            device = device.copy(
                profile = profile,
                fields = persistentMapOf(DeviceField.CASE_BATTERY to known(FieldValue.Number(65))),
            ),
        )
        assertEquals(QuickTileSummary(active = true, subtitle = "电量 65%"), quickTileSummary(single))
    }

    @Test
    fun tileIsInactiveUnlessASessionIsReady() {
        val connected = connectedI7State()
        val initializing = connected.copy(
            connection = ConnectionState.Connected(
                requireNotNull(connected.device).deviceId,
                1L,
                ModelProfile.I7_RS_V2,
                ConnectionPhase.INITIALIZING,
            ),
        )
        assertEquals(QuickTileSummary(active = false, subtitle = "正在连接…"), quickTileSummary(initializing))
        assertEquals(QuickTileSummary(active = false, subtitle = "未连接"), quickTileSummary(disconnectedAndroidState()))
    }

    private fun known(value: FieldValue) = FieldReading.Known(value, "")

    private fun withFields(state: ControlState, vararg fields: Pair<DeviceField, FieldReading>): ControlState {
        val device = requireNotNull(state.device)
        return state.copy(device = device.copy(fields = device.fields.puttingAll(persistentMapOf(*fields))))
    }

    private fun withQuickPanel(
        initialState: ControlState,
        style: InterfaceStyle,
        block: suspend (QuickHarness) -> Unit,
    ) = runOpenRoseUiTest(400, 1000) {
        val model = FakeOpenRoseModel(
            mutableStateOf(initialState),
            mutableStateOf(UiSettings(interfaceStyle = style, appearance = Appearance.LIGHT)),
        )
        val panel = QuickHarness(this, model)
        setContent {
            QuickPanel(
                model,
                PrimaryDeviceSelection(),
                dynamicColor = false,
                onOpenApp = { panel.openAppCount++ },
                onDismiss = { panel.dismissCount++ },
            )
        }
        block(panel)
    }

    private class QuickHarness(scene: OpenRoseUiTestScene, val model: FakeOpenRoseModel) : OpenRoseUiTestHarness(scene) {
        var openAppCount = 0
        var dismissCount = 0
    }
}

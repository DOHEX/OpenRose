@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.click
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.CirculationCommand
import io.github.dohex.openrose.circulation.CirculationResult
import io.github.dohex.openrose.circulation.HandoffStatus
import io.github.dohex.openrose.circulation.PeerDirectory
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.circulation.RemoteReading
import io.github.dohex.openrose.circulation.RemoteSessionReference
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.WindowDestination
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
    fun panelOpensDiagnosticsForFailuresAndHidesReconnectWhileBluetoothIsOff() {
        for (style in InterfaceStyle.entries) {
            val failed = connectedI7State().copy(lastError = ControlError(ControlErrorCode.NOT_APPLIED, "写入失败"))
            withQuickPanel(failed, style) { panel ->
                panel.render()
                panel.clickText("查看诊断")
                assertEquals(WindowDestination.DIAGNOSTICS, panel.destinations.single())
            }
            val base = disconnectedAndroidState()
            val disabled = base.copy(
                bondedDevices = persistentListOf(bonded),
                platform = base.platform.copy(transport = TransportAvailability.Disabled),
            )
            withQuickPanel(disabled, style) { panel ->
                panel.render()
                assertTrue(panel.hasText("系统蓝牙已关闭"))
                assertFalse(panel.hasText("重新连接"))
            }
        }
    }

    @Test
    fun equalizerChoiceWritesThePresetAndIsSelectedOnlyOnceTheHeadsetConfirms() {
        val base = connectedI7State()
        val current = "pop"
        val currentLabel = "弱水经典 · POP"
        val other = "hifi"
        val otherLabel = "原声再现 · HIFI"
        for (style in InterfaceStyle.entries) {
            val state = withFields(base, DeviceField.EQ to known(FieldValue.Text(current)))
            withQuickPanel(state, style) { panel ->
                panel.render()
                assertTrue(currentLabel in panel.tagTexts(QUICK_EQUALIZER_MENU_TAG), "current preset on the row in $style")
                assertFalse(panel.hasRadioText(otherLabel), "options are collapsed in $style")

                panel.openEqualizerMenu()
                assertTrue(panel.hasSelectedText(currentLabel), "confirmed preset selected in $style")
                assertFalse(panel.hasSelectedText(otherLabel), "other preset not selected in $style")
                panel.clickText(currentLabel, Role.RadioButton)
                panel.render()
                assertEquals(emptyList<Pair<DeviceSetting, FieldValue>>(), panel.model.settingWrites, "confirmed preset in $style")
                assertFalse(panel.hasRadioText(currentLabel), "reselecting also closes the menu in $style")

                panel.openEqualizerMenu()
                panel.clickText(otherLabel, Role.RadioButton)
                panel.render()
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.EQ to FieldValue.Text(other)),
                    panel.model.settingWrites,
                    "write in $style",
                )

                assertFalse(panel.hasRadioText(otherLabel), "choosing closes the menu in $style")
                assertTrue(currentLabel in panel.tagTexts(QUICK_EQUALIZER_MENU_TAG), "still the confirmed preset in $style")

                panel.model.mutableState.value = withFields(base, DeviceField.EQ to known(FieldValue.Text(other)))
                panel.render()
                assertTrue(otherLabel in panel.tagTexts(QUICK_EQUALIZER_MENU_TAG), "read-back updates the row in $style")
                panel.openEqualizerMenu()
                assertTrue(panel.hasSelectedText(otherLabel), "confirmed after read-back in $style")
                assertFalse(panel.hasSelectedText(currentLabel), "previous preset released in $style")
                assertEquals(0, panel.dismissCount, "the panel stays open after a change in $style")
            }
        }
    }

    @Test
    fun equalizerRowIsDisabledWhileASettingIsPending() {
        val base = connectedI7State()
        val device = requireNotNull(base.device)
        val current = device.writablePresetOptions().first()
        val pending = withFields(base, DeviceField.EQ to known(FieldValue.Text(current.value))).copy(
            settingOperations = listOf(
                SettingOperation(
                    id = 1L,
                    sessionId = device.sessionId,
                    setting = DeviceSetting.EQ,
                    requestedValue = FieldValue.Text(device.writablePresetOptions().last().value),
                    stage = SettingStage.WRITTEN,
                ),
            ).toPersistentList(),
        )
        for (style in InterfaceStyle.entries) {
            withQuickPanel(pending, style) { panel ->
                panel.render()
                assertTrue(panel.isTagDisabled(QUICK_EQUALIZER_MENU_TAG), "menu disabled while pending in $style")
                assertTrue(current.label in panel.tagTexts(QUICK_EQUALIZER_MENU_TAG), "row retains the confirmed preset in $style")
                assertTrue(panel.hasText("正在处理设置，等待设备确认"), "pending state shown in $style")
            }
        }
    }

    @Test
    fun unknownEqualizerAllowsOpeningAndDismissingTheMenuWithoutSelectingAPreset() {
        val base = connectedI7State()
        val unknown = withFields(base, DeviceField.EQ to FieldReading.Unknown("ff"))
        val device = requireNotNull(base.device)
        val labels = device.writablePresetOptions().map { it.label }
        for (style in InterfaceStyle.entries) {
            withQuickPanel(unknown, style) { panel ->
                panel.render()
                assertTrue("当前状态未知" in panel.tagTexts(QUICK_EQUALIZER_MENU_TAG), "unknown preset on the row in $style")
                panel.openEqualizerMenu()
                for (label in labels) {
                    assertTrue(panel.hasRadioText(label), "writable preset $label appears in $style")
                    assertFalse(panel.hasSelectedText(label), "no confirmed preset in $style")
                }
                panel.tapOutsideMenu()
                panel.render()
                assertFalse(panel.hasRadioText(labels.first()), "dismissed the menu in $style")
                assertEquals(0, panel.dismissCount, "dismissing the menu keeps the panel open in $style")
                assertTrue(panel.model.settingWrites.isEmpty(), "dismissal sends no command in $style")
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
                assertFalse(panel.hasTag(QUICK_EQUALIZER_MENU_TAG), "no equalizer menu in $style")
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
    fun aRemoteHeadsetCanChangeNoiseModeWithoutMovingAudioAndOnlyAnExplicitTransferPullsIt() {
        val address = "00:10:20:30:40:50"
        val headset = bonded.copy(classicAddress = address)
        val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(headset))
        val peers = mapOf("desktop" to "工作站")
        val session = RemoteSessionReference("desktop", DeviceId(address), "active-session")
        for (style in InterfaceStyle.entries) {
            var reading = RemoteReading(
                RemoteControlStatus.READY,
                deviceName = "ROSE EARFREE i5",
                session = session,
                mode = NoiseMode.ANC,
                modes = listOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY),
                left = 85,
                right = 80,
            )
            val writes = mutableListOf<CirculationCommand.SetNoise>()
            val pulls = mutableListOf<String>()
            val client = CirculationClient { command ->
                when (command) {
                    CirculationCommand.Directory -> PeerDirectory(peers, peers)

                    is CirculationCommand.Handoff -> HandoffStatus(available = true)

                    is CirculationCommand.Read -> reading

                    is CirculationCommand.SetNoise -> {
                        writes += command
                        reading.copy(mode = command.mode).also { reading = it }
                    }

                    is CirculationCommand.Pull -> {
                        pulls += command.address
                        CirculationResult(error = "电脑正在使用耳机麦克风")
                    }

                    else -> error("Unexpected request $command")
                }
            }
            withQuickPanel(state, style, client) { panel ->
                panel.render()
                assertTrue(panel.hasText("音源设备 · 工作站"))
                assertFalse(panel.hasText("重新连接"))
                panel.clickText("通透")
                panel.render()
                assertEquals(listOf(CirculationCommand.SetNoise(session, NoiseMode.TRANSPARENCY)), writes)
                assertTrue(pulls.isEmpty())
                panel.clickText("改连这部手机")
                panel.render()
                assertEquals(listOf(address), pulls)
                assertTrue(panel.hasText("电脑正在使用耳机麦克风"))
                assertTrue(panel.hasText("音源设备 · 工作站"))
                assertTrue(panel.model.connectRequests.isEmpty())

                panel.model.mutableState.value = withLocalHeadsetControl(state, headset)
                panel.render()
                assertEquals(1, panel.countText("降噪模式"))
                assertTrue(panel.hasText("游戏模式"))
                panel.clickText("降噪")
                panel.render()
                assertEquals(CirculationCommand.SetNoise(session, NoiseMode.ANC), writes.last())
                assertTrue(panel.model.settingWrites.isEmpty())
            }
        }
    }

    private fun withLocalHeadsetControl(state: ControlState, headset: DeviceCandidate): ControlState {
        val device = requireNotNull(connectedI7State().device).copy(deviceId = headset.deviceId, profile = ModelProfile.I5_ZT)
        return state.copy(
            connection = ConnectionState.Connected(device.deviceId, device.sessionId, device.profile, ConnectionPhase.READY),
            device = device,
        )
    }

    @Test
    fun audioAlreadyOnThePhoneRecoversControlWithoutRequestingATransfer() {
        val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(bonded), audioDeviceId = bonded.deviceId)
        for (style in InterfaceStyle.entries) {
            withQuickPanel(state, style) { panel ->
                panel.render()
                assertTrue(panel.hasText("音源设备 · 这部手机"))
                assertFalse(panel.hasText("改连这部手机"))
                assertFalse(panel.hasText("将耳机连接到这部手机"))
                panel.clickText("恢复耳机控制")
                assertEquals(listOf(bonded.deviceId), panel.model.connectRequests)
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
                assertEquals(WindowDestination.ADD_DEVICE, panel.destinations.single(), "opens the adding flow in $style")
                assertEquals(1, panel.openAppCount, "open app in $style")
            }
        }
    }

    @Test
    fun scrimDarkensWithTheOpeningSheetAndLightensAsItCloses() {
        for (style in InterfaceStyle.entries) {
            withQuickPanel(disconnectedAndroidState(), style) { panel ->
                panel.render(0)
                val initial = panel.snapshot()
                assertEquals(Color.White.toArgb(), initial.getRGB(8, 8), "no scrim on the first frame in $style")
                assertEquals(Color.White.toArgb(), initial.getRGB(200, 950), "sheet starts below the window in $style")
                panel.render(1)
                val opening = panel.snapshot().getRGB(8, 8) and 0xff
                panel.render(30)
                val resting = panel.snapshot().getRGB(8, 8) and 0xff
                assertTrue(opening > resting, "the exposed white background darkens as the sheet enters in $style")

                panel.clickTag(QUICK_PANEL_SCRIM_TAG)
                panel.render(2)
                val closing = panel.snapshot().getRGB(8, 8) and 0xff
                assertTrue(closing in (resting + 1)..254, "the scrim fades while the sheet leaves in $style")
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
                diagnostics = device.diagnostics.copy(fields = persistentMapOf(DeviceField.CASE_BATTERY to known(FieldValue.Number(65)))),
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
        return state.copy(device = device.copy(diagnostics = device.diagnostics.copy(fields = device.diagnostics.fields.puttingAll(persistentMapOf(*fields)))))
    }

    private fun withQuickPanel(
        initialState: ControlState,
        style: InterfaceStyle,
        circulation: CirculationClient? = null,
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
                onOpenApp = { panel.destinations += it },
                onDismiss = { panel.dismissCount++ },
                modifier = Modifier.background(Color.White),
                circulation = circulation,
            )
        }
        block(panel)
    }

    private class QuickHarness(scene: OpenRoseUiTestScene, val model: FakeOpenRoseModel) : OpenRoseUiTestHarness(scene) {
        val destinations = mutableListOf<WindowDestination?>()
        val openAppCount get() = destinations.size
        var dismissCount = 0

        fun openEqualizerMenu() {
            val center = tagBounds(QUICK_EQUALIZER_MENU_TAG).center
            scene.touchInput { click(center) }
            render()
        }

        fun tapOutsideMenu() = scene.touchInput { click(Offset(8f, 8f)) }
    }
}

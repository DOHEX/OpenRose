@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.dohex.openrose.circulation.*
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.WindowDestination
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayPanelUiTest {
    private val first = trayCandidate("rose-1", ModelProfile.I5_ZT)
    private val second = trayCandidate("rose-2", ModelProfile.I7_RS_V2)

    @Test
    fun localSoundSettingsRemainAvailableWithoutCirculation() {
        val i7 = connectedI7State().let { state ->
            val device = requireNotNull(state.device)
            val target = trayCandidate(device.deviceId.value, device.profile)
            state.copy(
                platform = state.platform.copy(hostPlatform = HostPlatform.WINDOWS),
                connectionTarget = target,
                bondedDevices = persistentListOf(target),
                audioDeviceId = target.deviceId,
            )
        }
        for (state in listOf(initializingDesktopI5(), i7)) {
            for (style in InterfaceStyle.entries) {
                var opened = 0
                val sound = DesktopSoundActions(currentOutput = { "桌面音箱" }, openSoundSettings = { opened++ })
                withTrayPanel(state, style, sound = sound) { panel ->
                    panel.render()
                    assertTrue(panel.hasText("输出 · 桌面音箱"))
                    panel.clickText("声音设置")
                    panel.render()
                    assertEquals(1, opened)
                }
            }
        }
    }

    @Test
    fun reopeningPanelShowsLastKnownDesktopOutputBeforeNextPollCompletes() {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        var calls = 0
        val sound = DesktopSoundActions(
            currentOutput = {
                calls++
                if (calls == 1) "桌面音箱" else gate.await()
            },
            openSoundSettings = {},
        )
        val state = initializingDesktopI5()
        withTrayPanel(state, InterfaceStyle.MIUIX, sound = sound) { panel ->
            panel.render()
            assertTrue(panel.hasText("输出 · 桌面音箱"))
        }
        withTrayPanel(state, InterfaceStyle.MIUIX, sound = sound) { panel ->
            panel.render()
            assertTrue(panel.hasText("输出 · 桌面音箱"))
        }
    }

    @Test
    fun connectedPanelShowsBatteryAndNoiseMode() {
        val connected = connectedI7State()
        val device = requireNotNull(connected.device)
        val headset = trayCandidate(device.deviceId.value, device.profile).copy(name = device.profile.displayName)
        val state = connected.copy(
            platform = connected.platform.copy(hostPlatform = HostPlatform.LINUX),
            bondedDevices = persistentListOf(headset),
            audioDeviceId = device.deviceId,
        )
        for (style in InterfaceStyle.entries) {
            withTrayPanel(state, style) { panel ->
                panel.render()
                assertTrue(panel.hasText("左 90%"))
                assertTrue(panel.hasText("右 90%"))
                assertTrue(panel.hasText("盒 80%"))
                assertTrue(panel.hasSelectedText("降噪"), "confirmed noise mode in $style")
                assertTrue(panel.hasTappableText("通透"), "noise mode choices in $style")
                panel.capturePanel("local-headset", style)
            }
        }
    }

    @Test
    fun connectedPanelShowsDefaultCustomControlsAndOpensCustomization() {
        val connected = connectedI7State()
        val device = requireNotNull(connected.device)
        val headset = trayCandidate(device.deviceId.value, device.profile).copy(name = device.profile.displayName)
        val state = connected.copy(
            platform = connected.platform.copy(hostPlatform = HostPlatform.LINUX),
            bondedDevices = persistentListOf(headset),
            audioDeviceId = device.deviceId,
            device = device.copy(
                diagnostics = device.diagnostics.copy(
                    fields = device.diagnostics.fields.puttingAll(
                        mapOf(
                            DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
                            DeviceField.ANC_LEVEL to FieldReading.Known(FieldValue.Number(1), "01"),
                            DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00"),
                            DeviceField.EQ to FieldReading.Known(FieldValue.Text("pop"), "00"),
                        ),
                    ),
                ),
            ),
        )
        for (style in InterfaceStyle.entries) {
            withTrayPanel(state, style) { panel ->
                panel.render()
                assertTrue(panel.hasText("降噪档位"), "noise level shown in $style")
                assertTrue(panel.hasText("游戏模式"), "game mode shown in $style")
                assertTrue(panel.hasText("均衡器"), "equalizer shown in $style")
                assertTrue(panel.hasText("自定义"), "customize action shown in $style")

                panel.clickText("游戏模式")
                panel.render()
                assertTrue(
                    DeviceSetting.GAME_MODE to FieldValue.Toggle(true) in panel.model.settingWrites,
                    "toggling game mode writes setting in $style",
                )

                panel.clickTag(TRAY_EQUALIZER_ROW_TAG)
                panel.render()
                val options = device.writablePresetOptions()
                val target = options.first { it.value != "pop" }
                panel.clickText(target.label)
                panel.render()
                assertTrue(
                    DeviceSetting.EQ to FieldValue.Text(target.value) in panel.model.settingWrites,
                    "inline equalizer expands and writes preset in $style",
                )

                panel.clickText("自定义")
                panel.render()
                assertEquals(WindowDestination.TRAY_PANEL_SETTINGS, panel.destinations.last())
            }
        }
    }

    @Test
    fun customTrayPanelSettingsHideLevelsAndReorderControls() {
        val connected = connectedI7State()
        val device = requireNotNull(connected.device)
        val headset = trayCandidate(device.deviceId.value, device.profile).copy(name = device.profile.displayName)
        val state = connected.copy(
            platform = connected.platform.copy(hostPlatform = HostPlatform.LINUX),
            bondedDevices = persistentListOf(headset),
            audioDeviceId = device.deviceId,
            device = device.copy(
                diagnostics = device.diagnostics.copy(
                    fields = device.diagnostics.fields.puttingAll(
                        mapOf(
                            DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
                            DeviceField.ANC_LEVEL to FieldReading.Known(FieldValue.Number(1), "01"),
                            DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00"),
                            DeviceField.EQ to FieldReading.Known(FieldValue.Text("pop"), "00"),
                        ),
                    ),
                ),
            ),
        )
        val customSettings = TrayPanelSettings(
            showNoiseLevels = false,
            enabledControls = setOf(TrayPanelControl.EQUALIZER, TrayPanelControl.GAME_MODE),
            controlOrder = listOf(
                TrayPanelControl.EQUALIZER,
                TrayPanelControl.GAME_MODE,
                TrayPanelControl.SPATIAL_AUDIO,
                TrayPanelControl.INDEPENDENT_MIC,
            ),
        )
        withTrayPanel(state, InterfaceStyle.MIUIX, trayPanelSettings = customSettings) { panel ->
            panel.render()
            assertFalse(panel.hasText("降噪档位"))
            val eqTop = panel.textBounds("均衡器").top
            val gameTop = panel.textBounds("游戏模式").top
            assertTrue(eqTop < gameTop, "expected equalizer ($eqTop) above game mode ($gameTop)")
        }
    }

    @Test
    fun trayPanelSettingsPageEditsOrderToggleAndResets() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(420, 900) {
                val settings = mutableStateOf(TrayPanelSettings.DEFAULT)
                setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        TrayPanelSettingsPage(
                            settings = settings.value,
                            onChange = { settings.value = it },
                        )
                    }
                }
                val harness = OpenRoseUiTestHarness(this)
                harness.render()
                harness.clickText("降噪／通透档位")
                harness.render()
                assertFalse(settings.value.showNoiseLevels, "toggles noise levels in $style")

                harness.clickContentDescription("上移 EQ 预设")
                harness.render()
                assertEquals(TrayPanelControl.EQUALIZER, settings.value.controlOrder.first(), "reorders equalizer up in $style")

                harness.clickText("恢复默认")
                harness.render()
                assertEquals(TrayPanelSettings.DEFAULT, settings.value, "resets to defaults in $style")
            }
        }
    }

    @Test
    fun selectingAnotherNoiseModeWritesItAndSelectingTheConfirmedOneDoesNot() {
        for (style in InterfaceStyle.entries) {
            val state = connectedI7State()
            val modes = traySummary(state, PrimaryDeviceSelection()).noiseModes
            val confirmed = modes.single { it.selected }
            val other = modes.first { !it.selected }
            withTrayPanel(state, style) { panel ->
                panel.render()

                if (style == InterfaceStyle.MATERIAL3) {
                    val group = panel.nodes().single {
                        it.config.contains(SemanticsProperties.SelectableGroup) &&
                            it.children.any { child ->
                                child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.contains(confirmed.label) }
                            }
                    }
                    val buttons = group.children.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                    assertEquals(6, buttons.size, "all six modes across both rows must form one choice")
                    assertEquals(2, buttons.map { it.boundsInRoot.top }.distinct().size)
                    assertEquals(1, buttons.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
                }
                panel.clickText(confirmed.label)
                assertEquals(emptyList<Pair<DeviceSetting, FieldValue>>(), panel.model.settingWrites, "confirmed mode in $style")

                panel.clickText(other.label)
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.NOISE_MODE to FieldValue.Text(other.mode.id)),
                    panel.model.settingWrites,
                    "other mode in $style",
                )
            }
        }
    }

    @Test
    fun reconnectConnectsThePrimaryDeviceTheDashboardWouldOffer() {
        for (style in InterfaceStyle.entries) {
            val selection = PrimaryDeviceSelection()
            val state = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
            selection.select(state, second.deviceId)
            withTrayPanel(state, style, selection) { panel ->
                panel.render()
                panel.clickText("重新连接")
                assertEquals(listOf(second.deviceId), panel.model.connectRequests, "primary device in $style")
            }
        }
    }

    @Test
    fun desktopCanRetryOnlyControlWhenAudioIsAlreadyConnected() {
        val base = disconnectedAndroidState()
        val state = base.copy(
            platform = base.platform.copy(hostPlatform = HostPlatform.LINUX),
            bondedDevices = persistentListOf(first),
            audioDeviceId = first.deviceId,
        )
        withTrayPanel(state, InterfaceStyle.MIUIX) { panel ->
            panel.render()
            assertFalse(panel.hasTappableText("将耳机连接到这台电脑"))
            panel.clickText("恢复耳机控制")
            assertEquals(listOf(first.deviceId), panel.model.connectRequests)
        }
        val loading = state.copy(connection = ConnectionState.Connecting(first.deviceId, 1L, ConnectionPhase.DISCOVERING))
        assertTrue("耳机已接入，正在恢复控制" in traySummary(loading, PrimaryDeviceSelection()).title)
    }

    @Test
    fun connectingPanelCanCancelTheAttempt() {
        for (style in InterfaceStyle.entries) {
            val state = disconnectedAndroidState().copy(
                bondedDevices = persistentListOf(first),
                connection = ConnectionState.Connecting(first.deviceId, 1L, ConnectionPhase.CONNECTING),
            )
            withTrayPanel(state, style) { panel ->
                panel.render()
                assertFalse(panel.hasText("重新连接"), "no reconnect while connecting in $style")
                panel.clickText("取消连接")
                assertEquals(1, panel.model.disconnectCount, "cancel in $style")
            }
        }
    }

    @Test
    fun withoutBondedDeviceAddHeadphonesShowsThemInTheMainWindow() {
        for (style in InterfaceStyle.entries) {
            withTrayPanel(disconnectedAndroidState(), style) { panel ->
                panel.render()
                assertFalse(panel.hasText("重新连接"), "nothing to reconnect in $style")
                panel.clickText("添加耳机")
                assertEquals(WindowDestination.ADD_DEVICE, panel.destinations.single(), "opens the adding flow in $style")
                assertEquals(1, panel.openAppCount, "main window opened in $style")
            }
        }
    }

    @Test
    fun openAppIsAlwaysAvailable() {
        for (style in InterfaceStyle.entries) {
            for (state in listOf(disconnectedAndroidState(), connectedI7State())) {
                withTrayPanel(state, style) { panel ->
                    panel.render()
                    panel.clickText("打开 OpenRose")
                    assertEquals(1, panel.openAppCount, "open app in $style")
                    assertEquals(null, panel.destinations.single(), "plain open does not navigate in $style")
                }
            }
        }
    }

    @Test
    fun reportedContentHeightShowsEveryButtonWithoutScrolling() {
        for (style in InterfaceStyle.entries) {
            for (state in listOf(disconnectedAndroidState(), connectedI7State())) {
                runOpenRoseUiTest(360, 1400) {
                    var reported = 0f
                    val model = FakeOpenRoseModel(
                        mutableStateOf(state),
                        mutableStateOf(UiSettings(interfaceStyle = style, appearance = Appearance.LIGHT)),
                    )
                    setContent {
                        TrayPanel(model, PrimaryDeviceSelection(), onOpenApp = {}, onContentHeight = { reported = it.value })
                    }
                    val panel = OpenRoseUiTestHarness(this)
                    panel.render()
                    val bottom = panel.clickableBounds("打开 OpenRose").bottom
                    assertTrue(reported - bottom in 0f..24f, "content height $reported fits the last button ending at $bottom in $style")
                }
            }
        }
    }

    @Test
    fun dashboardAndTrayAgreeOnThePrimaryDeviceAfterSwitchingAndDisconnecting() {
        for (style in InterfaceStyle.entries) {
            val selection = PrimaryDeviceSelection()
            val bonded = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
            withApp(bonded, style, selection) { app ->
                app.render()
                app.clickText("切换")
                app.state.value = bonded.copy(
                    connectionTarget = second,
                    connection = ConnectionState.Connecting(second.deviceId, 1L, ConnectionPhase.CONNECTING),
                )
                app.render()
                app.state.value = bonded
                app.render()

                val trayTarget = traySummary(bonded, selection).reconnectTarget
                app.clickText("切换")
                val offered = app.model.connectRequests.last()
                assertEquals(
                    listOf(first.deviceId, second.deviceId).single { it != offered },
                    trayTarget,
                    "tray and dashboard primary in $style",
                )
            }
        }
    }

    @Test
    fun aChoiceIsForgottenWhenTheDashboardLeaves() {
        for (style in InterfaceStyle.entries) {
            val bonded = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first, second))
            val leftSelection = PrimaryDeviceSelection()
            withApp(bonded, style, leftSelection) { app ->
                app.render()
                app.clickText("切换")
                app.state.value = connectedI7State().copy(bondedDevices = bonded.bondedDevices)
                app.render()
                app.state.value = bonded
                app.render()
                assertEquals(
                    first.deviceId,
                    traySummary(bonded, leftSelection).reconnectTarget,
                    "dashboard left composition in $style",
                )
            }
        }
    }

    @Test
    fun noiseModesAreDisabledWhileASettingIsPending() {
        val connected = connectedI7State()
        val selection = PrimaryDeviceSelection()
        val idle = traySummary(connected, selection).noiseModes
        assertTrue(idle.isNotEmpty())
        assertTrue(idle.all { it.enabled })
        assertEquals(1, idle.count { it.selected })

        val pending = connected.copy(
            settingOperations = listOf(
                SettingOperation(
                    id = 1L,
                    sessionId = requireNotNull(connected.device).sessionId,
                    setting = DeviceSetting.NOISE_MODE,
                    requestedValue = FieldValue.Text("transparency"),
                    stage = SettingStage.WRITTEN,
                ),
            ).toPersistentList(),
        )
        val waiting = traySummary(pending, selection).noiseModes
        assertEquals(idle.map { it.mode }, waiting.map { it.mode })
        assertTrue(waiting.none { it.enabled })
        assertEquals(idle.single { it.selected }.mode, waiting.single { it.selected }.mode)
    }

    @Test
    fun desktopTrayNamesEachBatteryEvenWhenTheHeadsetOmitsTheReading() {
        val connected = connectedI7State()
        val device = requireNotNull(connected.device)
        val state = connected.copy(
            platform = connected.platform.copy(hostPlatform = HostPlatform.LINUX),
            device = device.copy(diagnostics = device.diagnostics.copy(fields = persistentMapOf(DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(98), "62")))),
        )
        val summary = traySummary(state, PrimaryDeviceSelection())
        assertEquals("左耳 98% · 右耳 未知 · 充电盒 未知", summary.battery)
        assertEquals("L 98% · R - · C -", summary.compactBattery)
        withTrayPanel(state, InterfaceStyle.MIUIX) { panel ->
            panel.render()
            assertTrue(panel.hasText("左 98%"))
            assertTrue(panel.hasText("右 未知"))
            assertTrue(panel.hasText("盒 未知"))
        }
    }

    @Test
    fun batteryListsOnlyKnownReadings() {
        val connected = connectedI7State()
        val device = requireNotNull(connected.device)
        val partial = connected.copy(
            device = device.copy(
                diagnostics = device.diagnostics.copy(
                    fields = device.diagnostics.fields.puttingAll(
                        persistentMapOf(
                            DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(80), "80"),
                            DeviceField.RIGHT_BATTERY to FieldReading.Unknown("ff"),
                            DeviceField.CASE_BATTERY to FieldReading.Loading,
                        ),
                    ),
                ),
            ),
        )
        val battery = assertNotNull(traySummary(partial, PrimaryDeviceSelection()).battery)
        assertTrue("80%" in battery)
        assertFalse("右耳" in battery)

        val none = connected.copy(
            device = device.copy(
                diagnostics = device.diagnostics.copy(
                    fields = device.diagnostics.fields
                        .puttingAll(
                            persistentMapOf(
                                DeviceField.LEFT_BATTERY to FieldReading.Loading,
                                DeviceField.RIGHT_BATTERY to FieldReading.NotProvided(""),
                                DeviceField.CASE_BATTERY to FieldReading.Unknown("ff"),
                            ),
                        ),
                ),
            ),
        )
        assertNull(traySummary(none, PrimaryDeviceSelection()).battery)
    }

    @Test
    fun desktopTrayShowsTheConfirmedModeAndWriteFeedbackDuringInitialization() {
        val state = initializingDesktopI5()
        val summary = traySummary(state, PrimaryDeviceSelection())
        assertFalse(summary.connected)
        assertTrue(summary.title.contains("正在恢复控制"))
        assertEquals(NoiseMode.ANC, summary.noiseModes.single { it.selected }.mode)
        assertTrue(summary.noiseModes.all { it.enabled })
        val pending = SettingOperation(
            id = 1L,
            sessionId = requireNotNull(state.device).sessionId,
            setting = DeviceSetting.NOISE_MODE,
            requestedValue = FieldValue.Text("transparency"),
            stage = SettingStage.WRITTEN,
        )
        val expected = listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.NOISE_MODE to FieldValue.Text("transparency"))
        for (style in InterfaceStyle.entries) {
            withTrayPanel(state, style) { panel ->
                panel.render()
                panel.clickText("通透")
                assertEquals(expected, panel.model.settingWrites)
                panel.model.mutableState.value = state.copy(settingOperations = persistentListOf(pending))
                panel.render()
                assertTrue(panel.hasText("等待设备确认"))
                assertTrue(panel.hasDisabledText("通透"))
                assertTrue(panel.hasSelectedText("降噪"))
                panel.model.mutableState.value = state.copy(settingOperations = persistentListOf(pending.copy(stage = SettingStage.TIMED_OUT)))
                panel.render()
                assertTrue(panel.hasText("等待设备确认超时"))
            }
        }
    }

    @Test
    fun desktopMainWindowEnablesOnlyNoiseModeWhileInitializingOnAudio() {
        val state = initializingDesktopI5()
        val expected = listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.NOISE_MODE to FieldValue.Text("transparency"))
        for (style in InterfaceStyle.entries) {
            withApp(state, style, PrimaryDeviceSelection()) { app ->
                app.render()
                assertTrue(app.hasText("正在初始化并读取状态…"), "$style should still report initialization")
                assertTrue(app.hasTappableText("通透"), "$style should allow changing noise mode")
                assertFalse(app.hasTappableText("3 档"), "$style should keep ANC levels disabled")
                assertFalse(app.hasTappableText("游戏模式"), "$style should keep game mode disabled")
                app.clickText("通透")
                assertEquals(expected, app.model.settingWrites)
                app.state.value = state.copy(audioDeviceId = null)
                app.render()
                assertFalse(app.hasTappableText("通透"), "$style should disable early control when audio disconnects")
            }
        }
    }

    @Test
    fun onlyAReadySessionCountsAsConnected() {
        val selection = PrimaryDeviceSelection()
        val connected = connectedI7State()
        assertTrue(traySummary(connected, selection).connected)
        assertNull(traySummary(connected, selection).reconnectTarget)

        val initializing = connected.copy(
            connection = ConnectionState.Connected(
                requireNotNull(connected.device).deviceId,
                1L,
                ModelProfile.I7_RS_V2,
                ConnectionPhase.INITIALIZING,
            ),
        )
        val summary = traySummary(initializing, selection)
        assertFalse(summary.connected)
        assertTrue(summary.noiseModes.isEmpty())
        assertNull(summary.battery)
        assertNull(summary.reconnectTarget)

        val disconnected = disconnectedAndroidState().copy(bondedDevices = persistentListOf(first))
        assertEquals(first.deviceId, traySummary(disconnected, selection).reconnectTarget)
        assertFalse(traySummary(disconnected, selection).addDevice)
        assertTrue(traySummary(disconnectedAndroidState(), selection).addDevice)
    }

    private fun initializingDesktopI5(): ControlState {
        val base = disconnectedAndroidState()
        val device = DeviceState(
            deviceId = first.deviceId,
            sessionId = 73L,
            profile = ModelProfile.I5_ZT,
            capabilities = ModelProfile.I5_ZT.capabilities(),
            fields = persistentMapOf(
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
                DeviceField.ANC_LEVEL to FieldReading.Known(FieldValue.Number(3), "03"),
                DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00"),
            ),
        )
        return base.copy(
            platform = base.platform.copy(hostPlatform = HostPlatform.LINUX),
            connection = ConnectionState.Connecting(device.deviceId, device.sessionId, ConnectionPhase.INITIALIZING),
            connectionTarget = first,
            bondedDevices = persistentListOf(first),
            audioDeviceId = device.deviceId,
            device = device,
        )
    }

    private class AppHarness(
        scene: OpenRoseUiTestScene,
        val state: androidx.compose.runtime.MutableState<ControlState>,
        val model: FakeOpenRoseModel,
    ) : OpenRoseUiTestHarness(scene)

    private fun withApp(
        initialState: ControlState,
        style: InterfaceStyle,
        selection: PrimaryDeviceSelection,
        block: suspend (AppHarness) -> Unit,
    ) = runOpenRoseUiTest(420, 900) {
        val state = mutableStateOf(initialState)
        val model = FakeOpenRoseModel(state, mutableStateOf(UiSettings(interfaceStyle = style)))
        setContent { OpenRoseApp(model, BasicPlatformUi("Test"), primaryDeviceSelection = selection) }
        block(AppHarness(this, state, model))
    }
}

internal fun withTrayPanel(
    initialState: ControlState,
    style: InterfaceStyle,
    selection: PrimaryDeviceSelection = PrimaryDeviceSelection(),
    circulation: CirculationClient? = null,
    sound: DesktopSoundActions? = null,
    remoteHeadsets: State<List<RemoteHeadset>>? = null,
    trayPanelSettings: TrayPanelSettings = TrayPanelSettings.DEFAULT,
    block: suspend (TrayHarness) -> Unit,
) = runOpenRoseUiTest(340, 700) {
    val model = FakeOpenRoseModel(
        mutableStateOf(initialState),
        mutableStateOf(UiSettings(interfaceStyle = style, appearance = Appearance.LIGHT)),
    )
    val panel = TrayHarness(this, model)
    setContent {
        TrayPanel(
            model,
            selection,
            onOpenApp = { panel.destinations += it },
            onContentHeight = { panel.contentHeight = it.value },
            circulation = circulation,
            sound = sound,
            remoteHeadsets = remoteHeadsets?.value,
            trayPanelSettings = trayPanelSettings,
        )
    }
    block(panel)
}

internal class TrayHarness(scene: OpenRoseUiTestScene, val model: FakeOpenRoseModel) : OpenRoseUiTestHarness(scene) {
    var contentHeight = 0f
    val destinations = mutableListOf<WindowDestination?>()
    val openAppCount get() = destinations.size

    fun capturePanel(state: String, style: InterfaceStyle) {
        val directory = System.getenv("OPENROSE_SURFACE_DIR") ?: return
        val fittedHeight = kotlin.math.ceil(contentHeight).toInt()
        resize(340, fittedHeight)
        render()
        val image = snapshot().getSubimage(0, 0, 340, fittedHeight)
        val target = File(directory).apply { mkdirs() }
        ImageIO.write(image, "png", File(target, "tray-$state-${style.name.lowercase()}.png"))
    }
}

internal fun trayCandidate(id: String, profile: ModelProfile) = DeviceCandidate(
    deviceId = DeviceId(id),
    name = "Device $id",
    rssi = null,
    profile = profile,
    manufacturerDataHex = "",
)

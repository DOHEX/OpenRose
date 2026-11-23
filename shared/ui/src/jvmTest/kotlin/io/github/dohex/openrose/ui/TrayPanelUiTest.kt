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
                    assertTrue(panel.hasText("系统默认输出 · 桌面音箱"))
                    panel.clickText("打开系统声音设置")
                    panel.render()
                    assertEquals(1, opened)
                }
            }
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
                assertTrue(panel.hasText("左耳 90% · 右耳 90% · 充电盒 80%"))
                assertTrue(panel.hasSelectedText("降噪"), "confirmed noise mode in $style")
                assertTrue(panel.hasTappableText("通透"), "noise mode choices in $style")
                panel.capturePanel("local-headset", style)
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
                    val group = panel.nodes().single { it.config.contains(SemanticsProperties.SelectableGroup) }
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
                // Taller than any content, so nothing is clipped or scrolled while measuring.
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
                // The dashboard offers 切换 only for the device that is not its primary.
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
        // Only the device's read-back is ever selected, never the requested value.
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
        assertEquals("左耳 98% · 右耳 未知 · 充电盒 未知", traySummary(state, PrimaryDeviceSelection()).battery)
        withTrayPanel(state, InterfaceStyle.MIUIX) { panel ->
            panel.render()
            assertTrue(panel.hasText("左耳 98% · 右耳 未知 · 充电盒 未知"))
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

    /** The main window sharing [selection] with the tray; connecting does nothing, so the dashboard stays composed. */
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
    block: suspend (TrayHarness) -> Unit,
) = runOpenRoseUiTest(360, 700) {
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
        resize(360, fittedHeight)
        render()
        // captureToImage retains the test surface's initial size even after the scene is resized.
        val image = snapshot().getSubimage(0, 0, 360, fittedHeight)
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

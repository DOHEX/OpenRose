@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.noiseControlCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseConnectedHomeUiTest {
    @Test
    fun notificationNavigationReturnsToHeadphonesWithoutChangingTheControlSession() {
        for (style in InterfaceStyle.entries) {
            val initial = connectedState()
            withHarness(420, 900, initial, style, wholeApp = true) { harness ->
                harness.render()
                harness.clickTab("设置")
                harness.render()
                harness.openPersonalization()
                harness.clickText("组件风格")
                harness.render()
                harness.homeNavigationRequest.value++
                harness.render(frames = 120)
                assertTrue(harness.hasContentDescription("耳机菜单"))
                assertTrue(harness.hasText("已连接 · 控制会话已就绪"))
                assertFalse(harness.hasText("组件风格"))
                assertEquals(initial.connection, harness.state.value.connection)
                assertTrue(harness.connects.isEmpty() && harness.disconnects.isEmpty())
                assertTrue(harness.settingWrites.isEmpty() && harness.startScans.isEmpty())
            }
        }
    }

    @Test
    fun i7QuickModesIncludeAutomaticAndDynamicNoiseControl() {
        for (style in InterfaceStyle.entries) {
            val initial = connectedState()
            val device = requireNotNull(initial.device)
            val profile = ModelProfile.I7_RS_V2
            withHarness(420, 900, initial.copy(
                connection = ConnectionState.Connected(device.deviceId, SESSION_ID, profile, ConnectionPhase.READY),
                device = device.copy(profile = profile, capabilities = device.capabilities.copy(
                    noiseControl = noiseControlCapabilities(profile),
                ), fields = device.fields + (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("auto"), "auto"))),
            ), style) { harness ->
                harness.render()
                assertTrue(harness.hasSelectedText("自动"))
                assertTrue(harness.hasClickableText("动态降噪"))
                harness.clickText("动态降噪")
                assertEquals(DeviceSetting.NOISE_MODE, harness.settingWrites.single().first)
                assertEquals(FieldValue.Text("dynamic"), harness.settingWrites.single().second)
                assertTrue(harness.hasSelectedText("自动"))
            }
        }
    }

    @Test
    fun windQuickModeKeepsItsConfirmedSelectionUntilTheDeviceReportsAnotherMode() {
        for (style in InterfaceStyle.entries) {
            val initial = connectedState()
            val device = requireNotNull(initial.device)
            withHarness(420, 900, initial.copy(
                device = device.copy(fields = device.fields +
                    (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("wind"), "wind"))),
            ), style) { harness ->
                harness.render()
                assertTrue(harness.hasSelectedText("抗风噪"))
                harness.clickText("通透")
                val expectedWrites: List<Pair<DeviceSetting, FieldValue>> =
                    listOf(DeviceSetting.NOISE_MODE to FieldValue.Text("transparency"))
                assertEquals(expectedWrites, harness.settingWrites)
                harness.render()
                assertTrue(harness.hasSelectedText("抗风噪"), "a click alone does not confirm a new mode in $style")
                val current = requireNotNull(harness.state.value.device)
                harness.state.value = harness.state.value.copy(
                    device = current.copy(fields = current.fields +
                        (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("transparency"), "transparency"))),
                )
                harness.render()
                assertTrue(harness.hasSelectedText("通透"))
            }
        }
    }

    @Test
    fun connectedHomeDisconnectMenuReturnsToNearbyDevicesWithoutOtherCommands() {
        for (style in InterfaceStyle.entries) {
            for ((width, height) in listOf(420 to 900, 1200 to 1000)) {
                withHarness(width, height, connectedState(), style, wholeApp = true) { harness ->
                    harness.render()
                    assertTrue(harness.hasContentDescription("耳机菜单"), "connected home menu in $style at ${width}dp")
                    harness.clickContentDescription("耳机菜单")
                    harness.render()
                    assertTrue(harness.hasText("断开控制连接"))

                    val nextStyle = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX
                    harness.uiSettings.value = harness.uiSettings.value.copy(interfaceStyle = nextStyle)
                    harness.render()
                    assertTrue(harness.hasText("断开控制连接"), "the open menu survives style replacement in $style")

                    harness.clickText("断开控制连接")
                    assertEquals(1, harness.disconnects.size)
                    assertTrue(harness.connects.isEmpty())
                    assertTrue(harness.startScans.isEmpty())
                    assertTrue(harness.settingWrites.isEmpty())

                    harness.state.value = harness.state.value.copy(connection = ConnectionState.Disconnected)
                    harness.render()
                    assertTrue(harness.hasText("搜索并添加耳机"))
                    assertFalse(harness.hasText("设备详情"))
                }
            }
        }
    }

    @Test
    fun materialMenuClosesWhenEscapeFollowsKeyboardActivation() {
        runOpenRoseUiTest(420, 900) {
            val state = mutableStateOf(connectedState())
            val model = FakeOpenRoseModel(state, mutableStateOf(UiSettings(interfaceStyle = InterfaceStyle.MATERIAL3)))
            val menuExpanded = mutableStateOf(false)
            val scrollStates = mainPages.associateWith { ScrollState(0) }
            setContent {
                ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                    MaterialMainShell(
                        pagerState = rememberMainTabsPagerState(),
                        wide = false,
                        onNavigate = {},
                        scrollStates = scrollStates,
                        pageContentFor = {},
                        connectedHome = true,
                        menuExpanded = menuExpanded.value,
                        onMenuExpandedChange = { menuExpanded.value = it },
                        onDisconnect = model::disconnect,
                    )
                }
            }
            val harness = OpenRoseUiTestHarness(this)
            harness.render()
            harness.focus("耳机菜单")
            harness.sendKey(Key.Enter)
            harness.render()
            assertTrue(harness.hasText("断开控制连接"), "Enter opens the connected-headphones menu")

            harness.sendKey(Key.Escape)
            harness.render()
            assertFalse(harness.hasText("断开控制连接"), "Escape closes the Material 3 menu after keyboard activation")
            assertEquals(0, model.disconnectCount, "closing the menu does not disconnect the control session")
        }
    }

    @Test
    fun connectedHomePrioritizesModelConnectionBatteryNoiseAndDetails() {
        for (style in InterfaceStyle.entries) {
            withHarness(420, 900, connectedState(), style, wholeApp = true) { harness ->
                harness.render()
                assertTrue(harness.hasText("i5"))
                assertTrue(harness.hasText("控制连接"))
                assertTrue(harness.hasText("已连接 · 控制会话已就绪"))
                assertTrue(harness.hasText("左耳"))
                assertTrue(harness.hasText("86%"))
                assertTrue(harness.hasText("右耳"))
                assertTrue(harness.hasText("82%"))
                assertTrue(harness.hasText("充电盒"))
                assertTrue(harness.hasText("67%"))
                assertTrue(harness.hasText("降噪模式"))
                assertTrue(harness.hasText("已确认：主动降噪"))
                assertFalse(harness.hasText("ZT"), "model configuration stays out of the daily home in $style")
                assertFalse(harness.hasText("RS Common V2"), "model configuration stays out of the daily home in $style")

                harness.clickText("设备详情")
                harness.render()
                assertTrue(harness.hasText("型号配置"))
                assertTrue(harness.hasText("ZT"))
                assertTrue(harness.settingWrites.isEmpty())
            }
        }
    }

    @Test
    fun connectedHomePreservesUnknownLoadingAndPendingDeviceValues() {
        for (style in InterfaceStyle.entries) {
            withHarness(420, 900, connectedState(), style) { harness ->
                harness.render()
                assertTrue(harness.hasText("86%"))

                val state = harness.state.value
                val device = requireNotNull(state.device)
                harness.state.value = state.copy(
                    device = device.copy(fields = device.fields + mapOf(
                        DeviceField.LEFT_BATTERY to FieldReading.Unknown("ff"),
                        DeviceField.CASE_BATTERY to FieldReading.Unknown("ff"),
                        DeviceField.NOISE_MODE to FieldReading.Unknown("ff"),
                    )),
                )
                harness.render()
                assertTrue(harness.hasText("未知"), "unknown battery and mode values remain unknown in $style")
                assertTrue(harness.hasText("82%"), "a known right battery remains visible in $style")
                assertTrue(harness.hasText("当前状态未知"), "an unknown mode is not shown as a selection in $style")
                assertFalse(harness.hasSelectedText("降噪"), "an unknown mode has no selected option in $style")
                assertFalse(harness.hasText("67%"), "unknown case battery is not converted to a percentage in $style")
                assertTrue(harness.settingWrites.isEmpty())

                val current = harness.state.value
                val loadingDevice = requireNotNull(current.device)
                harness.state.value = current.copy(
                    connection = ConnectionState.Connected(
                        loadingDevice.deviceId,
                        loadingDevice.sessionId,
                        loadingDevice.profile,
                        ConnectionPhase.INITIALIZING,
                    ),
                    device = loadingDevice.copy(fields = loadingDevice.fields + mapOf(
                        DeviceField.LEFT_BATTERY to FieldReading.Loading,
                        DeviceField.CASE_BATTERY to FieldReading.Loading,
                    )),
                )
                harness.render()
                assertTrue(harness.hasText("读取中…"), "battery loading remains distinct from unknown in $style")
                assertTrue(harness.settingWrites.isEmpty())

                val pendingState = harness.state.value
                val pendingDevice = requireNotNull(pendingState.device)
                val pending = SettingOperation(
                    id = 63,
                    sessionId = pendingDevice.sessionId,
                    setting = DeviceSetting.NOISE_MODE,
                    requestedValue = FieldValue.Text("wind"),
                    stage = SettingStage.WRITTEN,
                )
                harness.state.value = pendingState.copy(
                    connection = ConnectionState.Connected(
                        pendingDevice.deviceId,
                        pendingDevice.sessionId,
                        pendingDevice.profile,
                        ConnectionPhase.READY,
                    ),
                    device = pendingDevice.copy(fields = pendingDevice.fields +
                        (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"))),
                    settingOperations = persistentListOf(pending),
                )
                harness.render()
                assertTrue(harness.hasText("等待设备确认"), "pending status remains visible in $style")
                assertTrue(harness.hasSelectedText("降噪"), "a pending request keeps the previously confirmed mode selected in $style")
                assertFalse(harness.hasSelectedText("抗风噪"), "the requested wind mode is not presented as confirmed in $style")
                assertTrue(harness.hasDisabledText("降噪"), "pending mode options remain disabled in $style")
                assertTrue(harness.settingWrites.isEmpty())
            }
        }
    }

    @Test
    fun connectedHomeFeatureEntriesFollowDeviceCapabilities() {
        for (style in InterfaceStyle.entries) {
            val state = connectedState()
            val device = requireNotNull(state.device)
            withHarness(1200, 1000, state, style) { harness ->
                harness.render()
                assertFalse(harness.hasText("均衡器"))
                assertFalse(harness.hasText("触控操作"))
                assertFalse(harness.hasText("音频与设备"))
                assertTrue(harness.hasText("设备详情"))

                harness.state.value = state.copy(
                    device = device.copy(capabilities = device.capabilities.copy(
                        writableSettings = persistentSetOf(
                            DeviceSetting.NOISE_MODE,
                            DeviceSetting.GAME_MODE,
                            DeviceSetting.TOUCH_ENABLED,
                            DeviceSetting.AUDIO_MODE,
                        ),
                    )),
                )
                harness.render()
                assertTrue(harness.hasText("游戏模式"))
                assertFalse(harness.hasText("均衡器"), "game-only capability has its own home control in $style")
                assertTrue(harness.hasText("触控操作"))
                assertTrue(harness.hasText("音频与设备"))
                assertTrue(harness.hasText("设备详情"))
                assertTrue(harness.settingWrites.isEmpty())
            }
        }
    }

    @Test
    fun gameModeCardRendersCompactSwitchWithGamepadIconAndHandlesToggling() {
        for (style in InterfaceStyle.entries) {
            val baseState = connectedState()
            val baseDevice = requireNotNull(baseState.device)
            val gameDevice = baseDevice.copy(
                capabilities = baseDevice.capabilities.copy(
                    writableSettings = baseDevice.capabilities.writableSettings + DeviceSetting.GAME_MODE,
                ),
                fields = baseDevice.fields + (DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00")),
            )
            withHarness(420, 900, baseState.copy(device = gameDevice), style) { harness ->
                harness.render()
                // Game mode card should render single-line title without "设备已确认" subtitle
                assertTrue(harness.hasText("游戏模式"), "game mode switch must be visible in $style")
                assertFalse(harness.hasText("设备已确认：关闭"), "confirmed game mode subtitle must not be present in $style")
                assertFalse(harness.hasText("设备已确认：开启"), "confirmed game mode subtitle must not be present in $style")

                // Click to toggle game mode
                assertTrue(harness.hasClickableText("游戏模式"), "game mode row should be clickable in $style")
                harness.clickText("游戏模式")
                harness.render()
                assertEquals(
                    listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.GAME_MODE to FieldValue.Toggle(true)),
                    harness.settingWrites,
                    "toggling game mode dispatches setting command in $style",
                )

                // When pending operation is in flight, toggle is disabled and pending feedback is shown
                val pendingOp = SettingOperation(
                    id = 101,
                    sessionId = gameDevice.sessionId,
                    setting = DeviceSetting.GAME_MODE,
                    requestedValue = FieldValue.Toggle(true),
                    stage = SettingStage.WRITTEN,
                )
                harness.state.value = harness.state.value.copy(
                    settingOperations = persistentListOf(pendingOp),
                )
                harness.render()
                assertTrue(
                    harness.nodes().any {
                        it.config.contains(SemanticsProperties.Disabled) &&
                            it.config.getOrNull(SemanticsProperties.ToggleableState) != null
                    },
                    "game mode toggle must be disabled during pending setting in $style",
                )

                // When operation confirms, state updates silently without blocking banner
                harness.state.value = harness.state.value.copy(
                    device = gameDevice.copy(
                        fields = gameDevice.fields + (DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(true), "01")),
                    ),
                    settingOperations = persistentListOf(
                        pendingOp.copy(stage = SettingStage.CONFIRMED, confirmedFields = persistentSetOf(DeviceField.GAME_MODE)),
                    ),
                )
                harness.render()
                assertFalse(harness.hasText("设备已确认设置"), "confirmation of game mode must be silent without banner in $style")
                assertFalse(harness.hasText("正在处理设置，等待设备确认"), "pending banner disappears after confirmation in $style")
                assertFalse(harness.hasText("设备已确认：开启"), "no redundant subtitle after confirmation in $style")
            }
        }
    }
    @Test
    fun unknownToggleRendersDisabledSwitchWithoutInventingConfirmation() {
        for (style in InterfaceStyle.entries) {
            val base = connectedState()
            val original = requireNotNull(base.device)
            val device = original.copy(
                capabilities = original.capabilities.copy(
                    writableSettings = original.capabilities.writableSettings + DeviceSetting.GAME_MODE,
                ),
                fields = original.fields + (DeviceField.GAME_MODE to FieldReading.Unknown("ff")),
            )
            withHarness(420, 900, base.copy(device = device), style) { harness ->
                harness.render()
                assertTrue(harness.hasText("游戏模式"))
                assertFalse(harness.hasText("当前状态未知"))
                assertFalse(harness.hasText("设备已确认：开启"))
                assertFalse(harness.hasText("设备已确认：关闭"))
                assertTrue(harness.nodes().any {
                    it.config.contains(SemanticsProperties.Disabled) &&
                        it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
                }, "an unread game mode toggle is disabled in $style")
                assertTrue(harness.settingWrites.isEmpty())

                harness.state.value = harness.state.value.copy(device = device.copy(fields = device.fields +
                    (DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00"))))
                harness.render()
                assertTrue(harness.hasText("游戏模式"))
                assertFalse(harness.hasText("设备已确认：关闭"))
                assertTrue(harness.hasClickableText("游戏模式"))
                harness.clickText("游戏模式")
                harness.render()
                assertEquals(listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.GAME_MODE to FieldValue.Toggle(true)), harness.settingWrites)
            }
        }
    }

    private fun connectedState(): ControlState {
        val profile = ModelProfile.I5_ZT
        val id = DeviceId("connected-home-i5")
        val device = DeviceState(
            deviceId = id,
            sessionId = SESSION_ID,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = persistentSetOf(
                    DeviceField.LEFT_BATTERY,
                    DeviceField.RIGHT_BATTERY,
                    DeviceField.CASE_BATTERY,
                    DeviceField.NOISE_MODE,
                ),
                writableSettings = persistentSetOf(DeviceSetting.NOISE_MODE),
                noiseControl = noiseControlCapabilities(profile),
            ),
            fields = persistentMapOf(
                DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(86), "56"),
                DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(82), "52"),
                DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(67), "43"),
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
            ),
        )
        return ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
            ),
            connection = ConnectionState.Connected(id, SESSION_ID, profile, ConnectionPhase.READY),
            device = device,
        )
    }

    private fun withHarness(
        width: Int,
        height: Int,
        initialState: ControlState,
        style: InterfaceStyle,
        wholeApp: Boolean = false,
        block: suspend (ConnectedHomeHarness) -> Unit,
    ) {
        val state = mutableStateOf(initialState)
        val uiSettings = mutableStateOf(UiSettings(interfaceStyle = style))
        val disconnects = mutableListOf<Unit>()
        val connects = mutableListOf<DeviceId>()
        val startScans = mutableListOf<Unit>()
        val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val model = FakeOpenRoseModel(state, uiSettings).apply {
            onDisconnect = { disconnects += Unit }
            onConnect = { connects += it }
            onStartScan = { startScans += Unit }
            onSet = { setting, value -> settingWrites += setting to value }
        }
        runOpenRoseUiTest(width, height) {
            setContent {
                if (wholeApp) {
                    OpenRoseApp(model, BasicPlatformUi("Test"))
                } else {
                    ProvideInterfaceStyle(uiSettings.value.interfaceStyle, dark = false) {
                        HeadphonesPage(model, BasicPlatformUi("Test"), {})
                    }
                }
            }
            block(ConnectedHomeHarness(this, state, uiSettings, disconnects, connects, startScans, settingWrites, model.homeNavigationRequests))
        }
    }

    private class ConnectedHomeHarness(
        scene: OpenRoseUiTestScene,
        val state: MutableState<ControlState>,
        val uiSettings: MutableState<UiSettings>,
        val disconnects: MutableList<Unit>,
        val connects: MutableList<DeviceId>,
        val startScans: MutableList<Unit>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
        val homeNavigationRequest: kotlinx.coroutines.flow.MutableStateFlow<Long>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 36L
    }
}

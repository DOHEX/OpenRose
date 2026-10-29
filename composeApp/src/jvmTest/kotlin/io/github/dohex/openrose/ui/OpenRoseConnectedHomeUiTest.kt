@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.noiseControlCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseConnectedHomeUiTest {
    @Test
    fun notificationNavigationReturnsToHeadphonesWithoutChangingTheControlSession() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initial = connectedState(style)
            val harness = createHarness(420, 900, initial)
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()
                harness.clickText("组件风格")
                harness.render()
                harness.homeNavigationRequest.value++
                harness.render()
                assertTrue(harness.hasContentDescription("耳机菜单"))
                assertTrue(harness.hasText("已连接 · 控制会话已就绪"))
                assertFalse(harness.hasText("组件风格"))
                assertEquals(initial.connection, harness.state.value.connection)
                assertTrue(harness.connects.isEmpty() && harness.disconnects.isEmpty())
                assertTrue(harness.settingWrites.isEmpty() && harness.startScans.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun i7QuickModesIncludeAutomaticAndDynamicNoiseControl() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initial = connectedState(style)
            val device = requireNotNull(initial.device)
            val profile = ModelProfile.I7_RS_V2
            val harness = createHarness(420, 900, initial.copy(
                connection = ConnectionState.Connected(device.deviceId, SESSION_ID, profile, ConnectionPhase.READY),
                device = device.copy(profile = profile, capabilities = device.capabilities.copy(
                    noiseControl = noiseControlCapabilities(profile),
                ), fields = device.fields + (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("auto"), "auto"))),
            ))
            try {
                harness.render()
                assertTrue(harness.hasSelectedText("自动"))
                assertTrue(harness.hasClickableText("动态降噪"))
                harness.clickText("动态降噪")
                assertEquals(DeviceSetting.NOISE_MODE, harness.settingWrites.single().first)
                assertEquals(FieldValue.Text("dynamic"), harness.settingWrites.single().second)
                assertTrue(harness.hasSelectedText("自动"))
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun windQuickModeKeepsItsConfirmedSelectionUntilTheDeviceReportsAnotherMode() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initial = connectedState(style)
            val device = requireNotNull(initial.device)
            val harness = createHarness(420, 900, initial.copy(
                device = device.copy(fields = device.fields +
                    (DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("wind"), "wind"))),
            ))
            try {
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
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun connectedHomeDisconnectMenuReturnsToNearbyDevicesWithoutOtherCommands() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for ((width, height) in listOf(420 to 900, 1200 to 1000)) {
                val harness = createHarness(width, height, connectedState(style))
                try {
                    harness.render()
                    assertTrue(harness.hasContentDescription("耳机菜单"), "connected home menu in $style at ${width}dp")
                    harness.clickContentDescription("耳机菜单")
                    harness.render()
                    assertTrue(harness.hasText("断开控制连接"))

                    val nextStyle = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX
                    harness.state.value = harness.state.value.copy(
                        settings = harness.state.value.settings.copy(interfaceStyle = nextStyle),
                    )
                    harness.render()
                    assertTrue(harness.hasText("断开控制连接"), "the open menu survives style replacement in $style")

                    harness.clickText("断开控制连接")
                    assertEquals(1, harness.disconnects.size)
                    assertTrue(harness.connects.isEmpty())
                    assertTrue(harness.startScans.isEmpty())
                    assertTrue(harness.settingWrites.isEmpty())

                    harness.state.value = harness.state.value.copy(connection = ConnectionState.Disconnected)
                    harness.render()
                    assertTrue(harness.hasText("附近设备"))
                    assertTrue(harness.hasText("扫描耳机"))
                    assertFalse(harness.hasText("设备详情"))
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun materialMenuClosesWhenEscapeFollowsKeyboardActivation() = runBlocking {
        val harness = createHarness(420, 900, connectedState(InterfaceStyle.MATERIAL3))
        try {
            harness.render()
            harness.focus("耳机菜单")
            harness.sendKey(Key.Enter)
            harness.render()
            assertTrue(harness.hasText("断开控制连接"), "Enter opens the connected-headphones menu")

            harness.sendKey(Key.Escape)
            harness.render()
            assertFalse(harness.hasText("断开控制连接"), "Escape closes the Material 3 menu after keyboard activation")
            assertTrue(harness.disconnects.isEmpty(), "closing the menu does not disconnect the control session")
        } finally {
            harness.close()
        }
    }

    @Test
    fun connectedHomePrioritizesModelConnectionBatteryNoiseAndDetails() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(420, 900, connectedState(style))
            try {
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
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun connectedHomePreservesUnknownLoadingAndPendingDeviceValues() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(420, 900, connectedState(style))
            try {
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
                    settingOperations = listOf(pending),
                )
                harness.render()
                assertTrue(harness.hasText("等待设备确认"), "pending status remains visible in $style")
                assertTrue(harness.hasSelectedText("降噪"), "a pending request keeps the previously confirmed mode selected in $style")
                assertFalse(harness.hasSelectedText("抗风噪"), "the requested wind mode is not presented as confirmed in $style")
                assertTrue(harness.hasDisabledText("降噪"), "pending mode options remain disabled in $style")
                assertTrue(harness.settingWrites.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun connectedHomeFeatureEntriesFollowDeviceCapabilities() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val state = connectedState(style)
            val device = requireNotNull(state.device)
            val harness = createHarness(1200, 1000, state)
            try {
                harness.render()
                assertFalse(harness.hasText("均衡器"))
                assertFalse(harness.hasText("触控操作"))
                assertFalse(harness.hasText("音频与设备"))
                assertTrue(harness.hasText("设备详情"))

                harness.state.value = state.copy(
                    device = device.copy(capabilities = device.capabilities.copy(
                        writableSettings = setOf(
                            DeviceSetting.NOISE_MODE,
                            DeviceSetting.GAME_MODE,
                            DeviceSetting.TOUCH_ENABLED,
                            DeviceSetting.AUDIO_MODE,
                        ),
                    )),
                )
                harness.render()
                assertTrue(harness.hasText("均衡器"))
                assertTrue(harness.hasText("触控操作"))
                assertTrue(harness.hasText("音频与设备"))
                assertTrue(harness.hasText("设备详情"))
                assertTrue(harness.settingWrites.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    private fun connectedState(style: InterfaceStyle): ControlState {
        val profile = ModelProfile.I5_ZT
        val id = DeviceId("connected-home-i5")
        val device = DeviceState(
            deviceId = id,
            sessionId = SESSION_ID,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = setOf(
                    DeviceField.LEFT_BATTERY,
                    DeviceField.RIGHT_BATTERY,
                    DeviceField.CASE_BATTERY,
                    DeviceField.NOISE_MODE,
                ),
                writableSettings = setOf(DeviceSetting.NOISE_MODE),
                noiseControl = noiseControlCapabilities(profile),
            ),
            fields = mapOf(
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
                module = ModuleAvailability.NotEnabled,
            ),
            connection = ConnectionState.Connected(id, SESSION_ID, profile, ConnectionPhase.READY),
            settings = UserSettings(interfaceStyle = style),
            device = device,
        )
    }

    private suspend fun createHarness(width: Int, height: Int, initialState: ControlState): ConnectedHomeHarness {
        val state = mutableStateOf(initialState)
        val homeNavigationRequest = mutableStateOf(0L)
        val disconnects = mutableListOf<Unit>()
        val connects = mutableListOf<DeviceId>()
        val startScans = mutableListOf<Unit>()
        val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        val scene = createOpenRoseUiTestScene(width, height) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings -> state.value = state.value.copy(settings = settings) },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = { startScans += Unit },
                onStopScan = {},
                onConnect = { connects += it },
                onDisconnect = { disconnects += Unit },
                onSet = { setting, value -> settingWrites += setting to value },
                homeNavigationRequest = homeNavigationRequest.value,
            )
        }
        return ConnectedHomeHarness(scene, state, disconnects, connects, startScans, settingWrites, homeNavigationRequest)
    }

    private class ConnectedHomeHarness(
        scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val disconnects: MutableList<Unit>,
        val connects: MutableList<DeviceId>,
        val startScans: MutableList<Unit>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
        val homeNavigationRequest: MutableState<Long>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 36L
    }
}

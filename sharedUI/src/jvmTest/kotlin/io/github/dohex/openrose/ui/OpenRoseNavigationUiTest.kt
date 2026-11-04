package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.audioControlCapabilities
import io.github.dohex.openrose.core.equalizerCapabilities
import io.github.dohex.openrose.core.noiseControlCapabilities
import io.github.dohex.openrose.core.promptVolumeLevels
import io.github.dohex.openrose.core.touchControlCapabilities
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseNavigationUiTest {
    @Test
    fun unknownToggleRendersDisabledSwitchWithoutInventingConfirmation() {
        for (style in InterfaceStyle.entries) {
            val base = state(connected = true)
            val device = requireNotNull(base.device)
            val initial = base.copy(device = device.copy(fields = device.fields +
                (DeviceField.GAME_MODE to FieldReading.Unknown("ff"))))
            withHarness(420, 900, initial.copy(settings = initial.settings.copy(interfaceStyle = style))) { harness ->
                harness.render()
                assertTrue(harness.hasText("游戏模式"))
                assertFalse(harness.hasText("当前状态未知"), "game mode toggle must not show unknown status text in $style")
                assertFalse(harness.hasText("设备已确认：开启"), "unconfirmed toggle must not claim confirmed in $style")
                assertFalse(harness.hasText("设备已确认：关闭"), "unconfirmed toggle must not claim confirmed in $style")
                assertTrue(
                    harness.nodes().any {
                        it.config.contains(SemanticsProperties.Disabled) &&
                            it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
                    },
                    "an unread game mode toggle must be disabled with off state in $style",
                )
                assertTrue(harness.settingWrites.isEmpty())

                // When the device reports known state, switch enables and clean single-line displays
                val knownDevice = device.copy(fields = device.fields +
                    (DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(false), "00")))
                harness.state.value = harness.state.value.copy(device = knownDevice)
                harness.render()

                assertTrue(harness.hasText("游戏模式"))
                assertFalse(harness.hasText("设备已确认：关闭"), "confirmed state must not show redundant subtitle in $style")
                assertTrue(harness.hasClickableText("游戏模式"), "known game setting must be clickable in $style")
                harness.clickText("游戏模式")
                harness.render()
                assertEquals(listOf<Pair<DeviceSetting, FieldValue>>(DeviceSetting.GAME_MODE to FieldValue.Toggle(true)), harness.settingWrites)
            }
        }
    }

    @Test
    fun narrowHomeOpensAvailableDiscoveryAndCanReturn() {
        withHarness(width = 420, height = 900, initialState = state(connected = false)) { harness ->
            harness.render()
            assertTrue(harness.hasText("添加你的 ROSE 耳机"))
            assertTrue(harness.hasContentDescription("添加耳机"))
            harness.clickContentDescription("添加耳机")
            harness.render()
            assertTrue(harness.hasText("附近设备"))
            harness.clickContentDescription("返回")
            harness.render()
            assertTrue(harness.hasText("添加你的 ROSE 耳机"))
        }
    }

    @Test
    fun wideSidebarNavigatesBetweenSettingsAndSystemIntegration() {
        withHarness(width = 1200, height = 1000, initialState = state(connected = false)) { harness ->
            harness.render()
            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("组件风格"))
            assertTrue(harness.hasText("强制使用 BLE"))
            assertTrue(harness.hasText("超级岛"))
            assertTrue(harness.hasText("焦点通知"))
            assertFalse(harness.hasText("运行状态与诊断"))

            harness.clickTab("状态")
            harness.render()
            assertFalse(harness.hasText("超级岛"))
            assertFalse(harness.hasText("焦点通知"))
            assertTrue(harness.hasText("运行状态与诊断"))

            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("组件风格"))
        }
    }

    @Test
    fun styleChangesPreserveSessionAndDisconnectClearsControls() {
        val operation = SettingOperation(
            id = 41,
            sessionId = SESSION_ID,
            setting = DeviceSetting.NOISE_MODE,
            requestedValue = FieldValue.Text("anc"),
            stage = SettingStage.CONFIRMED,
            confirmedFields = setOf(DeviceField.NOISE_MODE),
        )
        withHarness(
            width = 1200,
            height = 1400,
            initialState = state(connected = true, operations = listOf(operation)),
        ) { harness ->
            harness.render()

            harness.clickTab("设置")
            harness.render()
            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Material 3 Expressive")
            harness.render()
            harness.clickText("明暗模式")
            harness.render()
            harness.clickText("深色")
            harness.render()
            assertEquals(InterfaceStyle.MATERIAL3, harness.state.value.settings.interfaceStyle)
            assertEquals(Appearance.DARK, harness.state.value.settings.appearance)
            assertTrue(harness.settingsChanges.any { it.interfaceStyle == InterfaceStyle.MATERIAL3 })
            assertTrue(harness.settingsChanges.any { it.appearance == Appearance.DARK })
            assertTrue(harness.settingWrites.isEmpty(), "style and appearance changes must not call onSet")

            harness.clickTab("我的耳机")
            harness.render()
            assertTrue(harness.hasText("均衡器"))

            harness.clickText("设备详情")
            harness.render()
            assertTrue(harness.hasText("当前连接"))
            assertTrue(harness.hasText("设备状态"))

            harness.state.value = harness.state.value.copy(connection = ConnectionState.Disconnected)
            harness.render()
            assertTrue(harness.hasText("耳机已断开"))
            assertFalse(harness.hasText("当前连接"))
            assertFalse(harness.hasText("设备状态"))
            assertFalse(harness.hasText("均衡器 / 音效"))

            harness.clickTab("状态")
            harness.render()
            harness.clickText("运行状态与诊断")
            harness.render()
            assertTrue(harness.hasText("请求 #41：降噪模式"))
            assertTrue(harness.hasText("已结束会话 $SESSION_ID 的操作记录"))
            assertTrue(harness.settingWrites.isEmpty())
        }
    }

    private fun withHarness(
        width: Int,
        height: Int,
        initialState: ControlState,
        block: suspend (NavigationHarness) -> Unit,
    ) = runOpenRoseUiTest(width, height) {
        val state = mutableStateOf(initialState)
        val settingsChanges = mutableListOf<UserSettings>()
        val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
        setContent {
            OpenRoseApp(
                state = state.value,
                integration = androidIntegration,
                onSettingsChange = { settings ->
                    settingsChanges += settings
                    state.value = state.value.copy(settings = settings)
                },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = { state.value = state.value.copy(scan = io.github.dohex.openrose.core.ScanState.Scanning) },
                onStopScan = { state.value = state.value.copy(scan = io.github.dohex.openrose.core.ScanState.Idle) },
                onConnect = {},
                onDisconnect = {},
                onSet = { setting, value -> settingWrites += setting to value },
                onAddDeviceConnect = {
                    io.github.dohex.openrose.core.OperationResult.Failure(
                        io.github.dohex.openrose.core.ControlError(io.github.dohex.openrose.core.ControlErrorCode.FAILED, "测试连接失败"),
                    )
                },
            )
        }
        block(NavigationHarness(this, state, settingsChanges, settingWrites))
    }

    private fun state(
        connected: Boolean,
        operations: List<SettingOperation> = emptyList(),
    ): ControlState {
        val profile = ModelProfile.I7_RS_V2
        val deviceId = DeviceId("navigation-test-i7")
        val device = DeviceState(
            deviceId = deviceId,
            sessionId = SESSION_ID,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = DeviceField.entries.toSet(),
                writableSettings = DeviceSetting.entries.toSet(),
                noiseControl = noiseControlCapabilities(profile),
                equalizer = equalizerCapabilities(profile),
                touchControl = touchControlCapabilities(profile),
                audioControl = audioControlCapabilities(profile),
                promptVolumeLevels = promptVolumeLevels(profile),
                gainLevels = setOf(0, 1, 2),
                hearingProtectionLevels = (0..6).toSet(),
            ),
            fields = mapOf(
                DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(86), "56"),
                DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(82), "52"),
                DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(67), "43"),
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
                DeviceField.EQ to FieldReading.Known(FieldValue.Text("pop"), "pop"),
                DeviceField.CUSTOM_EQ to FieldReading.Unknown(""),
            ),
        )
        return ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                controlTransportApplicability = PlatformApplicability.APPLICABLE,
            ),
            connection = if (connected) {
                ConnectionState.Connected(deviceId, SESSION_ID, profile, ConnectionPhase.READY)
            } else {
                ConnectionState.Disconnected
            },
            settings = UserSettings(interfaceStyle = InterfaceStyle.MIUIX, appearance = Appearance.SYSTEM),
            device = device,
            settingOperations = operations,
        )
    }

    private fun assertProgress(harness: NavigationHarness, description: String, expected: Float) {
        val node = harness.nodes().firstOrNull { semantics ->
            semantics.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(description)
        } ?: error("No slider found with content description '$description'")
        val current = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current
            ?: error("Slider '$description' has no progress range semantics")
        assertTrue(abs(current - expected) < 0.01f, "Expected $description to remain $expected, got $current")
    }

    private class NavigationHarness(
        scene: OpenRoseUiTestScene,
        val state: MutableState<ControlState>,
        val settingsChanges: MutableList<UserSettings>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 17L
    }
}

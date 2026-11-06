package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.audioControlCapabilities
import io.github.dohex.openrose.core.equalizerCapabilities
import io.github.dohex.openrose.core.noiseControlCapabilities
import io.github.dohex.openrose.core.promptVolumeLevels
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.touchControlCapabilities
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseNavigationUiTest {

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
    fun wideSidebarNavigatesBetweenSettingsAndStatus() {
        withHarness(width = 1200, height = 1000, initialState = state(connected = false)) { harness ->
            harness.render()
            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("个性化"))
            assertTrue(harness.hasText("强制使用 BLE"))
            assertFalse(harness.hasText("运行状态与诊断"))

            harness.clickTab("状态")
            harness.render()
            assertTrue(harness.hasText("运行状态与诊断"))

            harness.clickTab("设置")
            harness.render()
            assertTrue(harness.hasText("个性化"))
        }
    }

    @Test
    fun settingsOpensPersonalizationWhichSurvivesStyleSwitchesAndReturnsToSettings() {
        for (style in InterfaceStyle.entries) {
            for (width in listOf(420, 1200)) {
                withHarness(width = width, height = 1000, initialState = state(connected = false), style = style) { harness ->
                    val case = "$style width=$width"
                    harness.render()
                    harness.clickTab("设置")
                    harness.render()
                    assertTrue(harness.hasText("组件风格、明暗模式、背景模糊与转场动效"), "Settings shows the personalization entry in $case")
                    assertFalse(harness.hasText("组件风格"), "Interface preferences are not on Settings in $case")

                    harness.openPersonalization()
                    assertTrue(harness.hasText("组件风格"), "Personalization holds the interface preferences in $case")
                    assertFalse(harness.hasText("强制使用 BLE"), "Control preferences stay on Settings in $case")

                    val next = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX
                    harness.clickText("组件风格")
                    harness.render()
                    harness.clickText(if (next == InterfaceStyle.MIUIX) "Miuix" else "Material 3 Expressive")
                    harness.render()
                    assertEquals(next, harness.uiSettings.value.interfaceStyle)
                    assertTrue(harness.hasText("组件风格"), "A style switch keeps Personalization open in $case")
                    assertFalse(harness.hasText("强制使用 BLE"), "A style switch does not fall back to Settings in $case")

                    harness.clickContentDescription("返回")
                    harness.render(frames = 120)
                    assertTrue(harness.hasText("强制使用 BLE"), "Back returns to Settings in $case")
                    assertFalse(harness.hasText("组件风格"), "Back leaves Personalization in $case")
                    assertTrue(harness.settingWrites.isEmpty(), "Personalization changes never write device settings in $case")
                }
            }
        }
    }

    @Test
    fun wideRailStaysStillWhileSecondaryPagesTransitionAndYieldsToAddDevice() {
        for (style in InterfaceStyle.entries) {
            withHarness(width = 1200, height = 1000, initialState = state(connected = false), style = style) { harness ->
                fun rail() = harness.nodes()
                    .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                    .map { it.boundsInRoot }
                harness.render()
                harness.clickTab("设置")
                harness.render(frames = 120)
                val settled = rail()
                assertEquals(3, settled.size, "one rail with three tabs in $style")
                val railRight = settled.maxOf { it.right }.toInt()
                fun railPixels(): List<Int> {
                    val image = harness.snapshot()
                    return image.getRGB(0, 0, railRight, image.height, null, 0, railRight).toList()
                }
                val settledPixels = railPixels()

                harness.clickText("个性化")
                harness.render(frames = 8)
                assertEquals(settled, rail(), "opening a secondary page must not move the rail in $style")
                assertTrue(settledPixels == railPixels(), "pages in transition must not draw over the rail in $style")
                harness.render(frames = 120)
                assertTrue(harness.hasText("组件风格"))
                assertEquals(settled, rail(), "the rail stays beside a secondary page in $style")

                harness.clickContentDescription("返回")
                harness.render(frames = 8)
                assertEquals(settled, rail(), "closing a secondary page must not move the rail in $style")
                assertTrue(settledPixels == railPixels(), "pages in transition must not draw over the rail in $style")
                harness.render(frames = 120)

                harness.clickTab("我的耳机")
                harness.render(frames = 120)
                harness.clickContentDescription("添加耳机")
                harness.render(frames = 120)
                assertTrue(harness.hasText("附近设备"))
                assertTrue(rail().isEmpty(), "the add-device flow takes the full width in $style")

                harness.clickContentDescription("返回")
                harness.render(frames = 120)
                assertEquals(settled, rail(), "the rail returns after the add-device flow in $style")
            }
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
            confirmedFields = persistentSetOf(DeviceField.NOISE_MODE),
        )
        withHarness(
            width = 1200,
            height = 1400,
            initialState = state(connected = true, operations = listOf(operation)),
        ) { harness ->
            harness.render()

            harness.clickTab("设置")
            harness.render()
            harness.openPersonalization()
            harness.clickText("组件风格")
            harness.render()
            harness.clickText("Material 3 Expressive")
            harness.render()
            harness.clickText("明暗模式")
            harness.render()
            harness.clickText("深色")
            harness.render()
            harness.clickContentDescription("返回")
            harness.render()
            assertEquals(InterfaceStyle.MATERIAL3, harness.uiSettings.value.interfaceStyle)
            assertEquals(Appearance.DARK, harness.uiSettings.value.appearance)
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
        style: InterfaceStyle = InterfaceStyle.MIUIX,
        block: suspend (NavigationHarness) -> Unit,
    ) = runOpenRoseUiTest(width, height) {
        val state = mutableStateOf(initialState)
        val uiSettings = mutableStateOf(UiSettings(interfaceStyle = style, appearance = Appearance.SYSTEM))
        val model = FakeOpenRoseModel(state, uiSettings)
        model.onStartScan = { state.value = state.value.copy(scan = io.github.dohex.openrose.core.ScanState.Scanning) }
        model.onStopScan = { state.value = state.value.copy(scan = io.github.dohex.openrose.core.ScanState.Idle) }
        setContent { OpenRoseApp(model = model, platformUi = BasicPlatformUi("Test")) }
        val settingsChanges = model.settingsChanges
        val settingWrites = model.settingWrites
        block(NavigationHarness(this, state, uiSettings, settingsChanges, settingWrites))
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
                readableFields = DeviceField.entries.toPersistentSet(),
                writableSettings = DeviceSetting.entries.toPersistentSet(),
                noiseControl = noiseControlCapabilities(profile),
                equalizer = equalizerCapabilities(profile),
                touchControl = touchControlCapabilities(profile),
                audioControl = audioControlCapabilities(profile),
                promptVolumeLevels = promptVolumeLevels(profile),
                gainLevels = persistentSetOf(0, 1, 2),
                hearingProtectionLevels = (0..6).toPersistentSet(),
            ),
            fields = persistentMapOf(
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
            device = device,
            settingOperations = operations.toPersistentList(),
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
        val uiSettings: MutableState<UiSettings>,
        val settingsChanges: MutableList<UiSettings>,
        val settingWrites: MutableList<Pair<DeviceSetting, FieldValue>>,
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 17L
    }
}

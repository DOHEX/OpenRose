package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.androidintegration.ModuleUnavailableCause
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemSettingsOwnershipUiTest {
    @Test
    fun statusPageSeparatesAnUnresponsiveModuleFromAnUnreadyOne() {
        val verdicts = listOf(
            ModuleAvailability.Available to "系统集成已连接",
            ModuleAvailability.NotEnabled to "系统集成未就绪",
            ModuleAvailability.Unresponsive to "系统集成无响应",
        )
        for (style in InterfaceStyle.entries) {
            for ((module, verdict) in verdicts) {
                withHarness(1200, style, module = module) { harness ->
                    harness.render()
                    harness.clickTab("状态")
                    harness.render()
                    assertTrue(harness.hasText(verdict), "$module is stated as \"$verdict\" in $style")
                    for ((_, other) in verdicts.filter { it.second != verdict }) {
                        assertFalse(harness.hasText(other), "$module is not also stated as \"$other\" in $style")
                    }
                }
            }
        }
    }

    @Test
    fun statusPageSaysWhatToDoOnlyWhereTheCauseIsKnown() {
        val advised = ModuleUnavailableCause.entries.map { ModuleAvailability.Unavailable("fixture", it) } +
            listOf(ModuleAvailability.NotEnabled, ModuleAvailability.Unresponsive)
        val advice = advised.map { requireNotNull(it.nextStep()) { "$it has no advice" } }
        assertEquals(advice.size, advice.toSet().size, "each cause is told apart: $advice")
        assertNull(ModuleAvailability.Available.nextStep())
        assertNull(ModuleAvailability.Unavailable("fixture").nextStep(), "an unexplained failure gets no invented advice")

        for (style in InterfaceStyle.entries) {
            for (module in advised + ModuleAvailability.Unavailable("fixture")) {
                withHarness(1200, style, module = module) { harness ->
                    harness.render()
                    harness.clickTab("状态")
                    harness.render()
                    module.nextStep()?.let { assertTrue(harness.hasText(it), "$module shows its advice in $style") }
                    assertTrue(harness.hasText("${module.unreadyClause()}。"), "$module keeps its reason in $style")
                }
            }
        }
    }

    @Test
    fun savedPreferenceMessageUsesThePageSurface() {
        for (style in InterfaceStyle.entries) {
            withHarness(1200, style, height = 1800) { harness ->
                harness.render()
                harness.clickTab("设置")
                harness.render()
                harness.clickText("焦点通知")
                harness.render()
                val message = harness.textBounds("偏好已保存，待系统就绪后生效。")
                val control = harness.clickableBounds("焦点通知")
                val image = harness.snapshot()
                val y = message.center.y.toInt()
                assertEquals(image.getRGB(control.right.toInt() + 24, y), image.getRGB(control.right.toInt() - 24, y),
                    "the saved preference message should use the surrounding page surface in $style")
            }
        }
    }

    @Test
    fun hyperOsPreferencesLiveInSettingsAndDiagnosticsIsReachedOnlyFromStatus() {
        for (style in InterfaceStyle.entries) {
            for (width in listOf(420, 1200)) {
                withHarness(width, style) { harness ->
                    harness.render()
                    harness.clickTab("设置")
                    harness.render()

                    assertTrue(harness.hasText("超级岛"), "HyperOS settings remain in Settings for $style at ${width}dp")
                    assertTrue(harness.hasText("焦点通知"), "focus notification belongs to Settings for $style at ${width}dp")
                    assertTrue(harness.hasText("官方"), "the existing island default remains visible for $style at ${width}dp")
                    assertTrue(harness.hasText("关于 OpenRose"), "About remains at the bottom of Settings for $style at ${width}dp")
                    assertFalse(harness.hasText("运行状态与诊断"), "Settings has no fixed diagnostics entry for $style at ${width}dp")
                    assertFalse(harness.hasTab("诊断与记录"), "the navigation rail has no diagnostics item for $style at ${width}dp")

                    harness.clickText("焦点通知")
                    harness.render()
                    assertFalse(harness.state.value.settings.focusNotificationEnabled, "the moved switch still saves its setting in $style")
                    assertTrue(harness.settingsChanges.any { !it.focusNotificationEnabled }, "the preference crosses the public callback in $style")
                    assertTrue(harness.hasText("偏好已保存，待系统就绪后生效。"), "unready integrations report a saved pending preference in $style")
                    assertFalse(harness.hasText("功能已生效"), "a save callback does not claim system presentation is active in $style")

                    harness.clickTab("状态")
                    harness.render()
                    assertFalse(harness.hasText("超级岛"), "Status contains no preferences for $style at ${width}dp")
                    assertFalse(harness.hasText("焦点通知"), "Status contains no notification control for $style at ${width}dp")
                    assertFalse(harness.hasText("强制使用 BLE"), "Status contains no connection preference for $style at ${width}dp")
                    assertFalse(harness.hasText("模块显示时机"), "Status contains no module timing control for $style at ${width}dp")
                    assertTrue(harness.hasText("系统集成未就绪"), "Status keeps the real readiness state for $style at ${width}dp")
                    assertTrue(harness.hasText("运行状态与诊断"), "Status owns the fixed diagnostics entry for $style at ${width}dp")
                    assertFalse(harness.hasTab("诊断与记录"), "the navigation rail has no diagnostics item for $style at ${width}dp")

                    harness.clickText("运行状态与诊断")
                    harness.render()
                    assertTrue(harness.hasText("系统模块"), "diagnostics remain reachable from Status for $style at ${width}dp")
                    assertFalse(harness.hasTab("诊断与记录"), "the diagnostics route is not a sidebar destination for $style at ${width}dp")
                    if (width >= 900) {
                        assertTrue(harness.hasSelectedTab("状态"), "Status remains selected while its diagnostics are open in $style")
                    }
                    assertTrue(harness.settingWrites.isEmpty())
                }
            }
        }
    }

    @Test
    fun moduleModeDoesNotInventTriggersWhenTheSavedSetIsEmpty() {
        for (style in InterfaceStyle.entries) {
            withHarness(
                width = 1200,
                style = style,
                settings = UserSettings(
                    interfaceStyle = style,
                    islandMode = IslandMode.OFF,
                    focusNotificationEnabled = true,
                    islandTriggers = emptySet(),
                ),
            ) { harness ->
                harness.render()
                harness.clickTab("设置")
                harness.render()
                assertFalse(harness.hasText("模块显示时机"), "trigger settings stay hidden outside module mode in $style")

                harness.clickText("超级岛")
                harness.render()
                harness.clickText("模块")
                harness.render()
                assertTrue(harness.hasText("模块显示时机"), "trigger settings appear in module mode in $style")
                assertTrue(harness.hasText("未选择显示时机，不自动触发。"), "an empty trigger set stays explicit in $style")
                assertTrue(harness.state.value.settings.islandTriggers.isEmpty())

                harness.clickText("超级岛")
                harness.render()
                harness.clickText("不显示")
                harness.render()
                assertFalse(harness.hasText("模块显示时机"))
                assertTrue(harness.state.value.settings.islandTriggers.isEmpty(), "hiding trigger settings does not reset values in $style")

                harness.clickText("超级岛")
                harness.render()
                harness.clickText("模块")
                harness.render()
                assertTrue(harness.hasText("未选择显示时机，不自动触发。"), "the empty trigger set survives hiding and restoring in $style")
                assertTrue(harness.settingWrites.isEmpty())
            }
        }
    }

    @Test
    fun readyIntegrationSaveStillDoesNotClaimSystemPresentationSucceeded() {
        for (style in InterfaceStyle.entries) {
            withHarness(
                width = 1200,
                style = style,
                module = ModuleAvailability.Available,
            ) { harness ->
                harness.render()
                harness.clickTab("设置")
                harness.render()
                harness.clickText("焦点通知")
                harness.render()

                assertTrue(harness.hasText("偏好已保存。系统状态请查看状态页。"), "ready integrations report save without claiming effect in $style")
                assertFalse(harness.hasText("功能已生效"), "module readiness alone does not prove system presentation in $style")
                assertFalse(harness.hasText("偏好已保存，待系统就绪后生效。"), "ready integrations do not report a pending readiness state in $style")
                assertTrue(harness.state.value.settings.focusNotificationEnabled.not())
            }
        }
    }

    private fun withHarness(
        width: Int,
        style: InterfaceStyle,
        settings: UserSettings = UserSettings(interfaceStyle = style, focusNotificationEnabled = true),
        module: ModuleAvailability = ModuleAvailability.Unavailable("兼容框架服务尚未连接"),
        height: Int = 1000,
        block: suspend (Harness) -> Unit,
    ) = runOpenRoseUiTest(width, height) {
        val settingsChanges = mutableListOf<UserSettings>()
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(
                    permission = PermissionState.GRANTED,
                    transport = TransportAvailability.Available,
                    hostPlatform = HostPlatform.ANDROID,
                    controlTransportApplicability = PlatformApplicability.APPLICABLE,
                ),
                settings = settings,
            ),
        )
        val integration = IntegrationState(
            platform = IntegrationPlatformState(
                module = module,
                systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
            ),
        )
        val settingWrites = mutableListOf<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>()
        setContent {
            OpenRoseApp(
                state = state.value,
                integration = integration,
                onSettingsChange = { nextSettings ->
                    settingsChanges += nextSettings
                    state.value = state.value.copy(settings = nextSettings)
                },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { setting, value -> settingWrites += setting to value },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
            )
        }
        block(Harness(this, state, settingsChanges, settingWrites))
    }

    private class Harness(
        scene: OpenRoseUiTestScene,
        val state: MutableState<ControlState>,
        val settingsChanges: List<UserSettings>,
        val settingWrites: List<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>,
    ) : OpenRoseUiTestHarness(scene)
}

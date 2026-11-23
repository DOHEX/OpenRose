package io.github.dohex.openrose.androidintegration.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.ui.*
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformApplicabilityUiTest {
    @Test
    fun unknownIntegrationApplicabilityIsReportedAsUnverified() {
        for (style in InterfaceStyle.entries) {
            withHarness(
                initialState = ControlState(
                    platform = PlatformState(
                        permission = PermissionState.GRANTED,
                        transport = TransportAvailability.Available,
                        hostPlatform = HostPlatform.ANDROID,
                        controlTransportApplicability = PlatformApplicability.APPLICABLE,
                    ),
                    settings = UserSettings(forceBle = true),
                ),
                initialUiSettings = UiSettings(interfaceStyle = style),
                initialIntegration = IntegrationState(
                    settings = IntegrationSettings(
                        islandMode = IslandMode.MODULE,
                        focusNotificationEnabled = true,
                        islandTriggers = persistentSetOf(),
                    ),
                    platform = IntegrationPlatformState(
                        module = ModuleAvailability.Available,
                        systemIntegrationApplicability = PlatformApplicability.UNKNOWN,
                    ),
                ),
            ) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.render()
                assertFalse(harness.hasText("超级岛"), "unverified integration applicability does not expose HyperOS controls in $style")
                assertFalse(harness.hasText("焦点通知"), "unverified integration applicability does not expose focus notification in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "known Android transport applicability remains independent in $style")

                harness.openPage("状态")
                harness.render()
                assertTrue(harness.hasText("系统集成适用性"), "unknown applicability is reported separately in $style")
                assertTrue(harness.hasText("尚未核实"), "unknown is not described as unsupported or unready in $style")

                harness.openPage("诊断")
                harness.render()
                assertFalse(harness.hasText("已连接系统模块"), "module state cannot resolve unknown platform applicability in $style")
            }
        }
    }

    @Test
    fun hyperOsSettingsStayVisibleWhileTheModuleIsUnready() {
        for (style in InterfaceStyle.entries) {
            withUnreadyHyperOsSettings(style) { harness ->
                assertTrue(harness.hasText("超级岛"), "HyperOS settings remain visible while integration is unready in $style")
                assertTrue(harness.hasText("焦点通知"), "HyperOS focus notification remains in Settings while unready in $style")
                assertTrue(harness.hasText("模块显示时机"), "the saved module mode controls its applicable settings in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "the Android host exposes its supported transport preference in $style")
                assertTrue(harness.hasText("附加设置入口"), "fusion settings destination entry remains in Settings while unready in $style")
                assertFalse(harness.hasText("选择 OpenRose 后"), "footnote text below additional settings entry is removed in $style")

                harness.openPage("状态")
                harness.render()
                assertTrue(harness.hasText("未就绪"), "readiness is reported separately from applicability in $style")
            }
        }
    }

    @Test
    fun hidingAndRestoringHyperOsSettingsPreservesTheSavedSelection() {
        for (style in InterfaceStyle.entries) {
            withUnreadyHyperOsSettings(style) { harness ->
                harness.hideIsland()
                harness.openPage("状态")
                harness.render()
                val savedSettings = harness.integration.value.settings
                harness.setApplicability(PlatformApplicability.NOT_APPLICABLE)
                assertFalse(harness.hasText("焦点通知"), "settings are hidden when the host becomes inapplicable in $style")
                assertEquals(savedSettings, harness.integration.value.settings, "hiding settings leaves saved values unchanged in $style")

                harness.openPage("设置")
                harness.render()
                assertFalse(harness.hasText("超级岛"), "HyperOS controls are hidden while inapplicable in $style")
                assertEquals(savedSettings, harness.integration.value.settings)

                harness.setApplicability(PlatformApplicability.APPLICABLE)
                assertTrue(harness.hasText("超级岛"), "settings return when the host becomes applicable again in $style")
                assertTrue(harness.hasText("焦点通知"), "focus notification returns with HyperOS applicability in $style")
                assertTrue(harness.hasText("不显示"), "the saved mode is restored when settings reappear in $style")
                assertEquals(savedSettings, harness.integration.value.settings)
                assertEquals(1, harness.settingsChanges.size, "visibility changes do not save settings in $style")
                assertTrue(harness.settingWrites.isEmpty(), "visibility changes do not send device settings in $style")
                assertTrue(harness.transportActions.isEmpty(), "visibility changes do not reconnect or scan in $style")
            }
        }
    }

    private fun withUnreadyHyperOsSettings(style: InterfaceStyle, block: suspend (Harness) -> Unit) {
        val initialSettings = IntegrationSettings(
            islandMode = IslandMode.MODULE,
            focusNotificationEnabled = true,
            islandTriggers = persistentSetOf(),
        )
        val platform = PlatformState(
            permission = PermissionState.GRANTED,
            transport = TransportAvailability.Available,
            hostPlatform = HostPlatform.ANDROID,
            controlTransportApplicability = PlatformApplicability.APPLICABLE,
        )
        withHarness(
            initialState = ControlState(platform = platform, settings = UserSettings(forceBle = true)),
            initialUiSettings = UiSettings(interfaceStyle = style),
            initialIntegration = IntegrationState(
                settings = initialSettings,
                platform = IntegrationPlatformState(
                    module = ModuleAvailability.Unavailable("兼容框架服务尚未连接"),
                    systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                ),
            ),
        ) { harness ->
            harness.render()
            harness.openPage("设置")
            harness.render()
            block(harness)
        }
    }

    private suspend fun Harness.hideIsland() {
        clickText("超级岛")
        render()
        clickText("不显示")
        render()
    }

    private fun Harness.setApplicability(value: PlatformApplicability) {
        integration.value = integration.value.copy(platform = integration.value.platform.copy(systemIntegrationApplicability = value))
        render()
    }

    private fun withHarness(
        initialState: ControlState,
        initialUiSettings: UiSettings,
        initialIntegration: IntegrationState,
        block: suspend (Harness) -> Unit,
    ) = runOpenRoseUiTest(1200, 1000) {
        val state = mutableStateOf(initialState)
        val uiSettings = mutableStateOf(initialUiSettings)
        val integration = MutableStateFlow(initialIntegration)
        val settingsChanges = mutableListOf<IntegrationSettings>()
        val settingWrites = mutableListOf<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>()
        val transportActions = mutableListOf<String>()
        val page = mutableStateOf("设置")
        val model = FakeOpenRoseModel(state, uiSettings)
        model.onStartScan = { transportActions += "start scan" }
        model.onStopScan = { transportActions += "stop scan" }
        model.onConnect = { transportActions += "connect" }
        model.onDisconnect = { transportActions += "disconnect" }
        model.onSet = { setting, value -> settingWrites += setting to value }
        val platform: PlatformUi = when (initialState.platform.hostPlatform) {
            HostPlatform.ANDROID -> androidTestPlatform(integration, onSettings = { settings ->
                settingsChanges += settings
                integration.value = integration.value.copy(settings = settings)
            })

            HostPlatform.WINDOWS -> BasicPlatformUi("Windows")

            HostPlatform.LINUX -> BasicPlatformUi("Linux")

            HostPlatform.UNKNOWN -> BasicPlatformUi("平台未核实")
        }
        setContent { AndroidTestPage(model, platform, page) }
        block(Harness(this, page, state, integration, settingsChanges, settingWrites, transportActions))
    }

    private class Harness(
        scene: OpenRoseUiTestScene,
        page: MutableState<String>,
        val state: MutableState<ControlState>,
        val integration: MutableStateFlow<IntegrationState>,
        val settingsChanges: MutableList<IntegrationSettings>,
        val settingWrites: MutableList<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>,
        val transportActions: MutableList<String>,
    ) : AndroidPageHarness(scene, page)
}

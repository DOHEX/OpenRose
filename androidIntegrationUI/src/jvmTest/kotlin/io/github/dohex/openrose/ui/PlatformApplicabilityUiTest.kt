package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentSetOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.ModuleAvailability
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
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
                assertFalse(harness.hasText("已连接系统模块"), "module state cannot resolve unknown platform applicability in $style")
                assertFalse(harness.hasText("焦点通知"))
            }
        }
    }

    @Test
    fun desktopStatusOmitsAndroidAndHyperOsOnlyContent() {
        for (style in InterfaceStyle.entries) {
            for ((hostPlatform, platformLabel) in listOf(
                HostPlatform.WINDOWS to "Windows",
                HostPlatform.LINUX to "Linux",
            )) {
                val platform = PlatformState(
                    permission = PermissionState.NOT_REQUIRED,
                    transport = TransportAvailability.Unavailable("$platformLabel 控制传输尚未实现"),
                    hostPlatform = hostPlatform,
                    controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
                )
                withHarness(
                    initialState = ControlState(
                        platform = platform,
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
                            systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                        ),
                    ),
                ) { harness ->
                    harness.render()
                    harness.openPage("设置")
                    harness.render()
                    assertFalse(harness.hasText("超级岛"), "$platformLabel must hide HyperOS settings in $style")
                    assertFalse(harness.hasText("焦点通知"), "$platformLabel must hide HyperOS notification settings in Settings in $style")
                    assertFalse(harness.hasText("强制使用 BLE"), "$platformLabel has no implemented control transport in $style")

                    harness.openPage("状态")
                    harness.render()
                    assertTrue(harness.hasText("$platformLabel 控制传输尚未实现"), "$platformLabel explains why it cannot control in $style")
                    assertTrue(harness.hasText("蓝牙传输"), "$platformLabel reports its transport state in $style")
                    assertFalse(harness.hasText("蓝牙权限"), "$platformLabel must not show Android permission items in $style")
                    assertFalse(harness.hasText("系统模块"), "$platformLabel must not expose HyperOS module state in $style")
                    assertFalse(harness.hasText("焦点通知"), "$platformLabel must not show HyperOS notification settings in $style")

                    harness.openPage("诊断")
                    harness.render()
                    assertFalse(harness.hasText("蓝牙权限"), "desktop diagnostics must omit Android permissions in $style")
                    assertFalse(harness.hasText("系统模块"), "desktop diagnostics must omit HyperOS module details in $style")
                    assertFalse(harness.hasText("系统桥接"), "desktop diagnostics must omit HyperOS bridge details in $style")
                    assertTrue(harness.settingWrites.isEmpty())
                    assertTrue(harness.transportActions.isEmpty())
                }
            }
        }
    }

    @Test
    fun ordinaryAndroidHidesHyperOsContentWithoutLosingItsExistingModuleState() {
        for (style in InterfaceStyle.entries) {
            val platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                controlTransportApplicability = PlatformApplicability.APPLICABLE,
            )
            withHarness(
                initialState = ControlState(
                    platform = platform,
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
                        systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                    ),
                ),
            ) { harness ->
                harness.render()
                harness.openPage("设置")
                harness.render()

                assertFalse(harness.hasText("超级岛"), "ordinary Android must not show HyperOS settings in $style")
                assertFalse(harness.hasText("焦点通知"), "ordinary Android must not show HyperOS notification settings in Settings in $style")
                assertFalse(harness.hasText("模块显示时机"), "ordinary Android must not show module timing in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "Android transport preference visibility is independent of HyperOS in $style")

                harness.openPage("状态")
                harness.render()
                assertFalse(harness.hasText("焦点通知"), "ordinary Android must not show HyperOS notification settings in $style")
                assertFalse(harness.hasText("已连接系统模块"), "a module state cannot make HyperOS applicable in $style")
                assertFalse(harness.hasText("蓝牙权限"), "a granted permission is not listed on the status page in $style")
                assertTrue(harness.hasText("蓝牙传输"), "Android system status retains the transport row in $style")
                assertTrue(harness.settingsChanges.isEmpty())
                assertTrue(harness.settingWrites.isEmpty())
                assertTrue(harness.transportActions.isEmpty())
            }
        }
    }

    @Test
    fun applicableHyperOsKeepsIntegrationSettingsAvailableUntilTheModuleIsReady() {
        for (style in InterfaceStyle.entries) {
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

                assertTrue(harness.hasText("超级岛"), "HyperOS settings remain visible while integration is unready in $style")
                assertTrue(harness.hasText("焦点通知"), "HyperOS focus notification remains in Settings while unready in $style")
                assertTrue(harness.hasText("模块显示时机"), "the saved module mode controls its applicable settings in $style")
                assertTrue(harness.hasText("未选择显示时机，不自动触发。"), "an explicit empty trigger set remains visible in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "the Android host exposes its supported transport preference in $style")
                assertTrue(harness.hasText("附加设置入口"), "fusion settings destination entry remains in Settings while unready in $style")
                assertFalse(harness.hasText("选择 OpenRose 后"), "footnote text below additional settings entry is removed in $style")

                harness.clickText("超级岛")
                harness.render()
                harness.clickText("不显示")
                harness.render()

                assertEquals(1, harness.settingsChanges.size, "an applicable but unready integration setting can still be saved in $style")
                assertEquals(IslandMode.OFF, harness.integration.value.settings.islandMode)
                assertEquals(emptySet(), harness.integration.value.settings.islandTriggers)
                assertTrue(harness.settingWrites.isEmpty(), "saving an integration preference must not send a device setting")
                harness.openPage("状态")
                harness.render()
                assertTrue(harness.hasText("系统集成未就绪"), "readiness is reported separately from applicability in $style")

                val savedSettings = harness.integration.value.settings
                harness.integration.value = harness.integration.value.copy(
                    platform = harness.integration.value.platform.copy(
                        systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                    ),
                )
                harness.render()
                assertFalse(harness.hasText("焦点通知"), "settings are hidden when the host becomes inapplicable in $style")
                assertEquals(savedSettings, harness.integration.value.settings, "hiding settings leaves saved values unchanged in $style")

                harness.openPage("设置")
                harness.render()
                assertFalse(harness.hasText("超级岛"), "HyperOS controls are hidden while inapplicable in $style")
                assertEquals(savedSettings, harness.integration.value.settings)

                harness.integration.value = harness.integration.value.copy(
                    platform = harness.integration.value.platform.copy(
                        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                    ),
                )
                harness.render()
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

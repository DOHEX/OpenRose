@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformApplicabilityUiTest {
    @Test
    fun unknownIntegrationApplicabilityIsReportedAsUnverified() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(
                initialState = ControlState(
                    platform = PlatformState(
                        permission = PermissionState.GRANTED,
                        transport = TransportAvailability.Available,
                        module = ModuleAvailability.Available,
                        hostPlatform = HostPlatform.ANDROID,
                        systemIntegrationApplicability = PlatformApplicability.UNKNOWN,
                        controlTransportApplicability = PlatformApplicability.APPLICABLE,
                    ),
                    settings = UserSettings(
                        interfaceStyle = style,
                        islandMode = io.github.dohex.openrose.core.IslandMode.MODULE,
                        focusNotificationEnabled = true,
                        islandTriggers = emptySet(),
                        forceBle = true,
                    ),
                ),
            )
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()
                assertFalse(harness.hasText("超级岛"), "unverified integration applicability does not expose HyperOS controls in $style")
                assertFalse(harness.hasText("焦点通知"), "unverified integration applicability does not expose focus notification in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "known Android transport applicability remains independent in $style")

                harness.clickTab("系统集成")
                harness.render()
                assertTrue(harness.hasText("系统集成适用性"), "unknown applicability is reported separately in $style")
                assertTrue(harness.hasText("尚未核实"), "unknown is not described as unsupported or unready in $style")
                assertFalse(harness.hasText("已连接系统模块"), "module state cannot resolve unknown platform applicability in $style")
                assertFalse(harness.hasText("焦点通知"))
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun desktopStatusOmitsAndroidAndHyperOsOnlyContent() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for ((hostPlatform, platformLabel) in listOf(
                HostPlatform.WINDOWS to "Windows",
                HostPlatform.LINUX to "Linux",
            )) {
                val platform = PlatformState(
                    permission = PermissionState.NOT_REQUIRED,
                    transport = TransportAvailability.Unavailable("$platformLabel 控制传输尚未实现"),
                    module = ModuleAvailability.Available,
                    hostPlatform = hostPlatform,
                    systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                    controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
                )
                val harness = createHarness(
                    initialState = ControlState(
                        platform = platform,
                        settings = UserSettings(
                            interfaceStyle = style,
                            islandMode = io.github.dohex.openrose.core.IslandMode.MODULE,
                            focusNotificationEnabled = true,
                            islandTriggers = emptySet(),
                            forceBle = true,
                        ),
                    ),
                )
                try {
                    harness.render()
                    harness.clickTab("设置")
                    harness.render()
                    assertFalse(harness.hasText("超级岛"), "$platformLabel must hide HyperOS settings in $style")
                    assertFalse(harness.hasText("焦点通知"), "$platformLabel must hide HyperOS notification settings in Settings in $style")
                    assertFalse(harness.hasText("强制使用 BLE"), "$platformLabel has no implemented control transport in $style")

                    harness.clickTab("系统集成")
                    harness.render()
                    assertTrue(harness.hasText(platformLabel), "$platformLabel status is identified in $style")
                    assertTrue(harness.hasText("蓝牙传输"), "$platformLabel reports its transport state in $style")
                    assertFalse(harness.hasText("蓝牙权限"), "$platformLabel must not show Android permission items in $style")
                    assertFalse(harness.hasText("系统模块"), "$platformLabel must not expose HyperOS module state in $style")
                    assertFalse(harness.hasText("焦点通知"), "$platformLabel must not show HyperOS notification settings in $style")

                    harness.clickText("运行状态与诊断")
                    harness.render()
                    assertFalse(harness.hasText("蓝牙权限"), "desktop diagnostics must omit Android permissions in $style")
                    assertFalse(harness.hasText("系统模块"), "desktop diagnostics must omit HyperOS module details in $style")
                    assertFalse(harness.hasText("系统桥接"), "desktop diagnostics must omit HyperOS bridge details in $style")
                    assertTrue(harness.settingWrites.isEmpty())
                    assertTrue(harness.transportActions.isEmpty())
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun ordinaryAndroidHidesHyperOsContentWithoutLosingItsExistingModuleState() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                module = ModuleAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                controlTransportApplicability = PlatformApplicability.APPLICABLE,
            )
            val harness = createHarness(
                initialState = ControlState(
                    platform = platform,
                    settings = UserSettings(
                        interfaceStyle = style,
                        islandMode = io.github.dohex.openrose.core.IslandMode.MODULE,
                        focusNotificationEnabled = true,
                        islandTriggers = emptySet(),
                        forceBle = true,
                    ),
                ),
            )
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()

                assertFalse(harness.hasText("超级岛"), "ordinary Android must not show HyperOS settings in $style")
                assertFalse(harness.hasText("焦点通知"), "ordinary Android must not show HyperOS notification settings in Settings in $style")
                assertFalse(harness.hasText("模块显示时机"), "ordinary Android must not show module timing in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "Android transport preference visibility is independent of HyperOS in $style")

                harness.clickTab("系统集成")
                harness.render()
                assertFalse(harness.hasText("焦点通知"), "ordinary Android must not show HyperOS notification settings in $style")
                assertFalse(harness.hasText("已连接系统模块"), "a module state cannot make HyperOS applicable in $style")
                assertTrue(harness.hasText("蓝牙权限"), "Android system status retains the permission row in $style")
                assertTrue(harness.hasText("蓝牙传输"), "Android system status retains the transport row in $style")
                assertTrue(harness.settingsChanges.isEmpty())
                assertTrue(harness.settingWrites.isEmpty())
                assertTrue(harness.transportActions.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun applicableHyperOsKeepsIntegrationSettingsAvailableUntilTheModuleIsReady() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val initialSettings = UserSettings(
                interfaceStyle = style,
                islandMode = io.github.dohex.openrose.core.IslandMode.MODULE,
                focusNotificationEnabled = true,
                islandTriggers = emptySet(),
                forceBle = true,
            )
            val platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                module = ModuleAvailability.Unavailable("兼容框架服务尚未连接"),
                hostPlatform = HostPlatform.ANDROID,
                systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                controlTransportApplicability = PlatformApplicability.APPLICABLE,
            )
            val harness = createHarness(
                initialState = ControlState(platform = platform, settings = initialSettings),
            )
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()

                assertTrue(harness.hasText("超级岛"), "HyperOS settings remain visible while integration is unready in $style")
                assertTrue(harness.hasText("焦点通知"), "HyperOS focus notification remains in Settings while unready in $style")
                assertTrue(harness.hasText("模块显示时机"), "the saved module mode controls its applicable settings in $style")
                assertTrue(harness.hasText("未选择显示时机，不自动触发。"), "an explicit empty trigger set remains visible in $style")
                assertTrue(harness.hasText("强制使用 BLE"), "the Android host exposes its supported transport preference in $style")

                harness.clickText("超级岛")
                harness.render()
                harness.clickText("不显示")
                harness.render()

                assertEquals(1, harness.settingsChanges.size, "an applicable but unready integration setting can still be saved in $style")
                assertEquals(io.github.dohex.openrose.core.IslandMode.OFF, harness.state.value.settings.islandMode)
                assertEquals(emptySet(), harness.state.value.settings.islandTriggers)
                assertTrue(harness.settingWrites.isEmpty(), "saving an integration preference must not send a device setting")
                harness.clickTab("系统集成")
                harness.render()
                assertTrue(harness.hasText("系统集成未就绪"), "readiness is reported separately from applicability in $style")

                val savedSettings = harness.state.value.settings
                harness.state.value = harness.state.value.copy(
                    platform = harness.state.value.platform.copy(
                        systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                    ),
                )
                harness.render()
                assertFalse(harness.hasText("焦点通知"), "settings are hidden when the host becomes inapplicable in $style")
                assertEquals(savedSettings, harness.state.value.settings, "hiding settings leaves saved values unchanged in $style")

                harness.clickTab("设置")
                harness.render()
                assertFalse(harness.hasText("超级岛"), "HyperOS controls are hidden while inapplicable in $style")
                assertEquals(savedSettings, harness.state.value.settings)

                harness.state.value = harness.state.value.copy(
                    platform = harness.state.value.platform.copy(
                        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                    ),
                )
                harness.render()
                assertTrue(harness.hasText("超级岛"), "settings return when the host becomes applicable again in $style")
                assertTrue(harness.hasText("焦点通知"), "focus notification returns with HyperOS applicability in $style")
                assertTrue(harness.hasText("不显示"), "the saved mode is restored when settings reappear in $style")
                assertEquals(savedSettings, harness.state.value.settings)
                assertEquals(1, harness.settingsChanges.size, "visibility changes do not save settings in $style")
                assertTrue(harness.settingWrites.isEmpty(), "visibility changes do not send device settings in $style")
                assertTrue(harness.transportActions.isEmpty(), "visibility changes do not reconnect or scan in $style")
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(initialState: ControlState): Harness {
        val state = mutableStateOf(initialState)
        val settingsChanges = mutableListOf<UserSettings>()
        val settingWrites = mutableListOf<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>()
        val transportActions = mutableListOf<String>()
        val scene = ImageComposeScene(
            width = 1200,
            height = 1000,
            density = Density(1f),
            coroutineContext = currentCoroutineContext(),
        ) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings ->
                    settingsChanges += settings
                    state.value = state.value.copy(settings = settings)
                },
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = { transportActions += "start scan" },
                onStopScan = { transportActions += "stop scan" },
                onConnect = { transportActions += "connect" },
                onDisconnect = { transportActions += "disconnect" },
                onSet = { setting, value -> settingWrites += setting to value },
            )
        }
        return Harness(scene, state, settingsChanges, settingWrites, transportActions)
    }

    private class Harness(
        private val scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val settingsChanges: MutableList<UserSettings>,
        val settingWrites: MutableList<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>,
        val transportActions: MutableList<String>,
    ) {
        private var nextFrameTimeNanos = 0L

        suspend fun render() {
            repeat(10) {
                kotlinx.coroutines.delay(35)
                scene.render(nextFrameTimeNanos).close()
                nextFrameTimeNanos += 35_000_000L
            }
        }

        fun close() = scene.close()

        fun nodes(): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                result += node
                node.children.forEach(::collect)
            }
            scene.semanticsOwners.forEach { collect(it.rootSemanticsNode) }
            return result
        }

        fun hasText(expected: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
        }

        fun clickTab(text: String) = clickText(text, expectedRole = Role.Tab)

        fun clickText(text: String, expectedRole: Role? = null) {
            for (textNode in nodes().filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }) {
                var current: SemanticsNode? = textNode
                while (current != null) {
                    val role = current.config.getOrNull(SemanticsProperties.Role)
                    val action = current.config.getOrNull(SemanticsActions.OnClick)?.action
                    if ((expectedRole == null || role == expectedRole) && action != null) {
                        check(action()) { "Click action for '$text' returned false" }
                        return
                    }
                    current = current.parent
                }
            }
            error("No clickable node found for '$text'")
        }
    }
}

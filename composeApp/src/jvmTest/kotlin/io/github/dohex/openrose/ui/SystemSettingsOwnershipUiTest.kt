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
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemSettingsOwnershipUiTest {
    @Test
    fun hyperOsPreferencesLiveInSettingsAndDiagnosticsIsReachedOnlyFromSystem() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for (width in listOf(420, 1200)) {
                val harness = createHarness(width, style)
                try {
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

                    harness.clickTab(if (width >= 900) "系统集成" else "系统")
                    harness.render()
                    assertFalse(harness.hasText("超级岛"), "System contains no preferences for $style at ${width}dp")
                    assertFalse(harness.hasText("焦点通知"), "System contains no notification control for $style at ${width}dp")
                    assertFalse(harness.hasText("强制使用 BLE"), "System contains no connection preference for $style at ${width}dp")
                    assertFalse(harness.hasText("模块显示时机"), "System contains no module timing control for $style at ${width}dp")
                    assertTrue(harness.hasText("系统集成未就绪"), "System keeps the real readiness state for $style at ${width}dp")
                    assertTrue(harness.hasText("运行状态与诊断"), "System owns the fixed diagnostics entry for $style at ${width}dp")
                    assertFalse(harness.hasTab("诊断与记录"), "the navigation rail has no diagnostics item for $style at ${width}dp")

                    harness.clickText("运行状态与诊断")
                    harness.render()
                    assertTrue(harness.hasText("系统模块"), "diagnostics remain reachable from System for $style at ${width}dp")
                    assertFalse(harness.hasTab("诊断与记录"), "the diagnostics route is not a sidebar destination for $style at ${width}dp")
                    if (width >= 900) {
                        assertTrue(harness.hasSelectedTab("系统集成"), "System remains selected while its diagnostics are open in $style")
                    }
                    assertTrue(harness.settingWrites.isEmpty())
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun moduleModeDoesNotInventTriggersWhenTheSavedSetIsEmpty() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(
                width = 1200,
                style = style,
                settings = UserSettings(
                    interfaceStyle = style,
                    islandMode = IslandMode.OFF,
                    focusNotificationEnabled = true,
                    islandTriggers = emptySet(),
                ),
            )
            try {
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
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun readyIntegrationSaveStillDoesNotClaimSystemPresentationSucceeded() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(
                width = 1200,
                style = style,
                module = ModuleAvailability.Available,
            )
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()
                harness.clickText("焦点通知")
                harness.render()

                assertTrue(harness.hasText("偏好已保存。系统状态请查看系统页。"), "ready integrations report save without claiming effect in $style")
                assertFalse(harness.hasText("功能已生效"), "module readiness alone does not prove system presentation in $style")
                assertFalse(harness.hasText("偏好已保存，待系统就绪后生效。"), "ready integrations do not report a pending readiness state in $style")
                assertTrue(harness.state.value.settings.focusNotificationEnabled.not())
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(
        width: Int,
        style: InterfaceStyle,
        settings: UserSettings = UserSettings(interfaceStyle = style, focusNotificationEnabled = true),
        module: ModuleAvailability = ModuleAvailability.Unavailable("兼容框架服务尚未连接"),
    ): Harness {
        val settingsChanges = mutableListOf<UserSettings>()
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(
                    permission = PermissionState.GRANTED,
                    transport = TransportAvailability.Available,
                    module = module,
                    hostPlatform = HostPlatform.ANDROID,
                    systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
                    controlTransportApplicability = PlatformApplicability.APPLICABLE,
                ),
                settings = settings,
            ),
        )
        val settingWrites = mutableListOf<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>()
        val scene = ImageComposeScene(
            width = width,
            height = 1000,
            density = Density(1f),
            coroutineContext = currentCoroutineContext(),
        ) {
            OpenRoseApp(
                state = state.value,
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
            )
        }
        return Harness(scene, state, settingsChanges, settingWrites)
    }

    private class Harness(
        private val scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val settingsChanges: List<UserSettings>,
        val settingWrites: List<Pair<io.github.dohex.openrose.core.DeviceSetting, io.github.dohex.openrose.core.protocol.FieldValue>>,
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

        fun hasTab(text: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        fun hasSelectedTab(text: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } &&
                node.config.getOrNull(SemanticsProperties.Selected) == true
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

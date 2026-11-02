@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import io.github.dohex.openrose.core.BackgroundControlBlock
import io.github.dohex.openrose.core.BackgroundServiceFact
import io.github.dohex.openrose.core.BackgroundServiceFacts
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.RunningNotification
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundControlUiTest {
    @Test
    fun theSwitchSavesTheIntentAndNeverClaimsRunningUntilThePlatformReportsIt() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(style)
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()
                assertTrue(harness.hasText("后台控制"), "background control is offered in $style")
                assertFalse(harness.hasText("后台状态"), "a disabled intent has no status in $style")

                harness.clickText("后台控制")
                harness.render()

                assertTrue(harness.settingsChanges.single().backgroundControlEnabled, "intent crosses the public callback in $style")
                assertTrue(harness.hasText("已启用，尚未运行；打开 OpenRose 后启动"), "saved intent is not ready in $style")
                assertFalse(harness.hasText("运行中"), "saving never reads as running in $style")

                harness.update { it.copy(platform = it.platform.copy(backgroundService = BackgroundServiceFacts(BackgroundServiceFact.Running))) }
                harness.render()
                assertTrue(harness.hasText("运行中"), "platform fact shows running in $style")

                harness.clickText("后台控制")
                harness.render()
                assertFalse(harness.state.value.settings.backgroundControlEnabled, "the same switch disables it in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun aMissingBluetoothPermissionIsExplainedWithAGrantAction() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(style, settings = UserSettings(interfaceStyle = style, backgroundControlEnabled = true), permission = PermissionState.DENIED)
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()

                assertTrue(harness.hasText("缺少蓝牙权限，后台控制无法运行"), "reason is shown in $style")
                assertFalse(harness.hasText("运行中"))
                harness.clickText("授予蓝牙权限")
                assertEquals(1, harness.permissionRequests, "grant action reaches the host in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun restrictedStartAndHiddenNotificationAreDisclosedHonestly() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(style, settings = UserSettings(interfaceStyle = style, backgroundControlEnabled = true))
            try {
                harness.render()
                harness.clickTab("设置")
                harness.update {
                    it.copy(platform = it.platform.copy(backgroundService = BackgroundServiceFacts(
                        BackgroundServiceFact.StartRejected(BackgroundControlBlock.BACKGROUND_START_RESTRICTED),
                    )))
                }
                harness.render()
                assertTrue(harness.hasText("系统限制了从后台启动，请打开 OpenRose 后重试"), "restriction is shown in $style")

                harness.update {
                    it.copy(platform = it.platform.copy(backgroundService = BackgroundServiceFacts(
                        BackgroundServiceFact.Running, RunningNotification.HIDDEN,
                    )))
                }
                harness.render()
                assertTrue(harness.hasText("系统已隐藏，后台控制仍在运行"), "hidden notification is disclosed in $style")
                harness.clickText("允许通知")
                assertEquals(1, harness.notificationRequests, "notification action reaches the host in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun hostsWithoutBackgroundServicesOfferNoSwitch() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(style, host = HostPlatform.LINUX)
            try {
                harness.render()
                harness.clickTab("设置")
                harness.render()
                assertFalse(harness.hasText("后台控制"), "no background switch on a desktop host in $style")
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(
        style: InterfaceStyle,
        settings: UserSettings = UserSettings(interfaceStyle = style),
        permission: PermissionState = PermissionState.GRANTED,
        host: HostPlatform = HostPlatform.ANDROID,
    ): Harness {
        val settingsChanges = mutableListOf<UserSettings>()
        val state = mutableStateOf(
            ControlState(
                platform = PlatformState(
                    permission = permission,
                    transport = TransportAvailability.Available,
                    hostPlatform = host,
                    systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE,
                    controlTransportApplicability = PlatformApplicability.APPLICABLE,
                ),
                settings = settings,
            ),
        )
        lateinit var harness: Harness
        val scene = createOpenRoseUiTestScene(1200, 2600) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { next ->
                    settingsChanges += next
                    state.value = state.value.copy(settings = next)
                },
                onRequestPermission = { harness.permissionRequests++ },
                onRequestNotificationPermission = { harness.notificationRequests++ },
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
            )
        }
        harness = Harness(scene, state, settingsChanges)
        return harness
    }

    private class Harness(
        scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val settingsChanges: List<UserSettings>,
    ) : OpenRoseUiTestHarness(scene) {
        var permissionRequests = 0
        var notificationRequests = 0
        fun update(transform: (ControlState) -> ControlState) { state.value = transform(state.value) }
    }
}

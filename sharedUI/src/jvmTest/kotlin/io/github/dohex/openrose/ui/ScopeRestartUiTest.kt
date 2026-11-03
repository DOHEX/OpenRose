@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeRestartUiTest {
    @Test
    fun confirmationCancellationProgressAndResultsAcrossStyles() {
        for (style in InterfaceStyle.entries) for (width in listOf(420, 1200)) {
            val result = CompletableDeferred<List<ScopeRestartResult>>()
            var requests = 0
            withHarness(style, HostPlatform.ANDROID, width, restart = { requests++; result.await() }) { harness ->
                harness.render()
                harness.clickTab("状态")
                harness.render()
                harness.clickContentDescription("重启作用域")
                harness.render()
                assertTrue(harness.hasText("将重启以下进程：\ncom.android.bluetooth\ncom.xiaomi.bluetooth\ncom.milink.service\n\n蓝牙连接可能中断。确认后将请求 Root 权限。"))
                harness.clickText("取消")
                harness.render()
                assertEquals(0, requests)
                harness.clickContentDescription("重启作用域")
                harness.render()
                harness.clickText("重启")
                harness.render()
                assertEquals(1, requests)
                assertTrue(harness.hasText("正在请求 Root 并处理作用域…"))
                result.complete(listOf(
                    ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.EXECUTED),
                    ScopeRestartResult("com.xiaomi.bluetooth", ScopeRestartStatus.NOT_RUNNING),
                    ScopeRestartResult("com.milink.service", ScopeRestartStatus.FAILED, "权限不足"),
                ))
                harness.render()
                assertTrue(harness.hasText("com.android.bluetooth：已执行"))
                assertTrue(harness.hasText("com.xiaomi.bluetooth：未运行，无需重启"))
                assertTrue(harness.hasText("com.milink.service：失败 · 权限不足"))
                assertTrue(harness.hasText("已执行仅表示命令已执行，不代表进程已恢复或模块已生效。"))
            }
        }
    }

    @Test
    fun desktopDoesNotOfferAndroidRestart() {
        for (style in InterfaceStyle.entries) {
            withHarness(style, HostPlatform.LINUX, 1200, restart = { error("unexpected restart") }) { harness ->
                harness.render()
                harness.clickTab("状态")
                harness.render()
                assertFalse(harness.hasContentDescription("重启作用域"))
            }
        }
    }

    private fun withHarness(
        style: InterfaceStyle,
        host: HostPlatform,
        width: Int,
        restart: suspend () -> List<ScopeRestartResult>,
        block: suspend (OpenRoseUiTestHarness) -> Unit,
    ) = runOpenRoseUiTest(width, 1000) {
        val state = ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                module = ModuleAvailability.NotEnabled,
                hostPlatform = host,
            ),
            settings = UserSettings(interfaceStyle = style, pageTransition = PageTransition.NONE),
        )
        setContent {
            OpenRoseApp(state, {}, {}, {}, {}, {}, {}, {}, { _, _ -> },
                onAddDeviceConnect = { error("unexpected connection") },
                onRestartIntegrationScopes = restart)
        }
        block(OpenRoseUiTestHarness(this))
    }
}

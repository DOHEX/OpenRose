@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeRestartUiTest {
    @Test
    fun confirmationCancellationProgressAndResultsAcrossStyles() = runBlocking {
        for (style in InterfaceStyle.entries) for (width in listOf(420, 1200)) {
            val result = CompletableDeferred<List<ScopeRestartResult>>()
            var requests = 0
            val harness = createHarness(style, HostPlatform.ANDROID, width) { requests++; result.await() }
            try {
                harness.render()
                harness.clickTab(if (width < 900) "系统" else "系统集成")
                harness.render()
                harness.clickContentDescription("重启作用域")
                harness.render()
                assertTrue(harness.hasText("蓝牙连接可能中断。确认后将请求 Root 权限。"))
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
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun desktopDoesNotOfferAndroidRestart() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(style, HostPlatform.LINUX, 1200) { error("unexpected restart") }
            try {
                harness.render()
                harness.clickTab("系统集成")
                harness.render()
                assertFalse(harness.hasContentDescription("重启作用域"))
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(style: InterfaceStyle, host: HostPlatform, width: Int, restart: suspend () -> List<ScopeRestartResult>): OpenRoseUiTestHarness {
        val state = ControlState(
            platform = PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                module = ModuleAvailability.NotEnabled,
                hostPlatform = host,
            ),
            settings = UserSettings(interfaceStyle = style, pageTransition = PageTransition.NONE),
        )
        val scene = createOpenRoseUiTestScene(width, 1000) {
            OpenRoseApp(state, {}, {}, {}, {}, {}, {}, {}, { _, _ -> },
                onAddDeviceConnect = { error("unexpected connection") },
                onRestartIntegrationScopes = restart)
        }
        return OpenRoseUiTestHarness(scene)
    }
}

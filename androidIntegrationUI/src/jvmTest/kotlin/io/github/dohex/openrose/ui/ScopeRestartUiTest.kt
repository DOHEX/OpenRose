@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.IntegrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeRestartUiTest {
    @Test
    fun confirmationCancellationProgressAndResultsAcrossStyles() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 1000) {
                val result = CompletableDeferred<List<ScopeRestartResult>>()
                var requests = 0
                val settings = mutableStateOf(UiSettings(interfaceStyle = style))
                val model = FakeOpenRoseModel(mutableStateOf(disconnectedAndroidState()), settings)
                val page = mutableStateOf("操作")
                val platform = androidTestPlatform(MutableStateFlow(IntegrationState()), restart = { requests++; result.await() }, scope = CoroutineScope(currentCoroutineContext()))
                setContent { AndroidTestPage(model, platform, page) }
                val harness = AndroidPageHarness(this, page)
                harness.render()
                harness.clickContentDescription("重启作用域")
                harness.render()
                harness.clickText("取消")
                harness.render()
                assertEquals(0, requests)
                harness.clickContentDescription("重启作用域")
                harness.render()
                harness.clickText("重启")
                yield()
                harness.render()
                assertEquals(1, requests)
                assertTrue(harness.hasText("正在请求 Root 并处理作用域…"))
                settings.value = settings.value.copy(interfaceStyle = InterfaceStyle.entries.first { it != style })
                harness.render()
                assertTrue(harness.hasText("正在请求 Root 并处理作用域…"))
                result.complete(listOf(
                    ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.EXECUTED),
                    ScopeRestartResult("com.xiaomi.bluetooth", ScopeRestartStatus.NOT_RUNNING),
                    ScopeRestartResult("com.milink.service", ScopeRestartStatus.FAILED, "权限不足"),
                ))
                yield()
                harness.render()
                assertTrue(harness.hasText("com.android.bluetooth：已执行"))
                assertTrue(harness.hasText("com.xiaomi.bluetooth：未运行，无需重启"))
                assertTrue(harness.hasText("com.milink.service：失败 · 权限不足"))
                assertTrue(harness.hasText("已执行仅表示命令已执行，不代表进程已恢复或模块已生效。"))
            }
        }
    }

    @Test
    fun failedRestartReportsEachAffectedProcess() = runOpenRoseUiTest(1200, 1000) {
        val page = mutableStateOf("操作")
        val platform = androidTestPlatform(
            MutableStateFlow(IntegrationState()),
            restart = { error("权限不足") },
            scope = CoroutineScope(currentCoroutineContext()),
        )
        setContent { AndroidTestPage(FakeOpenRoseModel(), platform, page) }
        val harness = AndroidPageHarness(this, page)
        harness.render()
        harness.clickContentDescription("重启作用域")
        harness.render()
        harness.clickText("重启")
        yield()
        harness.render()
        for (target in INTEGRATION_SCOPE_PACKAGES) {
            assertTrue(harness.hasText("$target：失败 · 权限不足"))
        }
    }

    @Test
    fun basicPlatformDoesNotOfferAndroidRestart() = runOpenRoseUiTest(1200, 1000) {
        val model = FakeOpenRoseModel()
        val page = mutableStateOf("操作")
        setContent { AndroidTestPage(model, BasicPlatformUi("Linux"), page) }
        val harness = AndroidPageHarness(this, page)
        harness.render()
        assertFalse(harness.hasContentDescription("重启作用域"))
    }
}

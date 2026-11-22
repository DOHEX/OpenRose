@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.androidintegration.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.IntegrationPlatformState
import io.github.dohex.openrose.androidintegration.IntegrationState
import io.github.dohex.openrose.androidintegration.scope.INTEGRATION_SCOPE_PACKAGES
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartPhase
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartResult
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartStatus
import io.github.dohex.openrose.androidintegration.scope.ScopeRestartUpdate
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val hyperOsIntegration = IntegrationState(
    platform = IntegrationPlatformState(
        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
        installedScopes = INTEGRATION_SCOPE_PACKAGES.toSet(),
    ),
)

class ScopeRestartUiTest {
    private suspend fun OpenRoseUiTestScene.restartPage(
        settings: MutableState<UiSettings>,
        restart: suspend (List<String>, (ScopeRestartUpdate) -> Unit) -> List<ScopeRestartResult>,
    ): AndroidPageHarness {
        val model = FakeOpenRoseModel(mutableStateOf(disconnectedAndroidState()), settings)
        val page = mutableStateOf("操作")
        val platform = androidTestPlatform(
            MutableStateFlow(hyperOsIntegration),
            restart = restart,
            scope = CoroutineScope(currentCoroutineContext()),
        )
        setContent { AndroidTestPage(model, platform, page) }
        return AndroidPageHarness(this, page).also { it.render() }
    }

    @Test
    fun cancellingScopeSelectionLeavesRestartAvailableWithoutRequestingRoot() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 1000) {
                var requests = 0
                val harness = restartPage(mutableStateOf(UiSettings(interfaceStyle = style))) { _, _ ->
                    requests++
                    emptyList()
                }
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
            }
        }
    }

    @Test
    fun progressShowsThePhaseAndStreamsResultsAcrossStyles() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 1000) {
                val authorized = CompletableDeferred<Unit>()
                val tick = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val settings = mutableStateOf(UiSettings(interfaceStyle = style))
                val harness = restartPage(settings) { _, emit ->
                    authorized.await()
                    emit(ScopeRestartPhase.Signalling)
                    emit(ScopeRestartResult("com.xiaomi.bluetooth", ScopeRestartStatus.NOT_RUNNING))
                    emit(ScopeRestartPhase.Waiting(8))
                    emit(ScopeRestartResult("com.milink.service", ScopeRestartStatus.RESTARTED))
                    tick.await()
                    emit(ScopeRestartPhase.Waiting(7))
                    finish.await()
                    listOf(
                        ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.FAILED, "权限不足"),
                        ScopeRestartResult("com.xiaomi.bluetooth", ScopeRestartStatus.NOT_RUNNING),
                        ScopeRestartResult("com.milink.service", ScopeRestartStatus.RESTARTED),
                    )
                }
                harness.clickContentDescription("重启作用域")
                harness.render()
                harness.clickText("重启")
                yield()
                harness.render()
                assertTrue(harness.hasText("正在请求 Root 授权…"))
                assertTrue(harness.hasContentDescription("等待中"))
                assertFalse(harness.hasContentDescription("正在重启"))
                assertFalse(harness.hasText("关闭"))

                authorized.complete(Unit)
                yield()
                harness.render()
                // All processes are signalled together: the unfinished one is in progress while the others already have results.
                assertTrue(harness.hasText("等待新进程出现，还剩 8 秒"))
                assertTrue(harness.hasContentDescription("未运行，无需重启"))
                assertTrue(harness.hasContentDescription("已重启"))
                assertTrue(harness.hasContentDescription("正在重启"))
                assertFalse(harness.hasContentDescription("等待中"))
                tick.complete(Unit)
                yield()
                harness.render()
                assertTrue(harness.hasText("等待新进程出现，还剩 7 秒"))
                // The countdown is the restart's own state, so switching the interface style keeps it.
                settings.value = settings.value.copy(interfaceStyle = InterfaceStyle.entries.first { it != style })
                harness.render()
                assertTrue(harness.hasText("等待新进程出现，还剩 7 秒"))
                assertTrue(harness.hasContentDescription("正在重启"))
                finish.complete(Unit)
                yield()
                harness.render()
                assertTrue(harness.hasContentDescription("失败"))
                assertEquals(1, harness.countText("权限不足"))
                assertFalse(harness.hasContentDescription("正在重启"))
                assertFalse(harness.hasText("等待新进程出现，还剩 7 秒"))
                assertTrue(harness.hasText("关闭"))
            }
        }
    }

    @Test
    fun onlyTickedProcessesAreRequestedAndEveryRowStaysInPlace() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(1200, 1000) {
                var requested = emptyList<String>()
                val finish = CompletableDeferred<Unit>()
                val settings = mutableStateOf(UiSettings(interfaceStyle = style))
                val page = mutableStateOf("操作")
                val platform = androidTestPlatform(MutableStateFlow(hyperOsIntegration), restart = { targets, emit ->
                    requested = targets
                    emit(ScopeRestartPhase.Signalling)
                    emit(ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.FAILED, "permission denied"))
                    emit(ScopeRestartPhase.Waiting(8))
                    finish.await()
                    listOf(
                        ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.FAILED, "permission denied"),
                        ScopeRestartResult("com.milink.service", ScopeRestartStatus.TERMINATED),
                    )
                }, scope = CoroutineScope(currentCoroutineContext()))
                setContent { AndroidTestPage(FakeOpenRoseModel(mutableStateOf(disconnectedAndroidState()), settings), platform, page) }
                val harness = AndroidPageHarness(this, page)
                harness.render()
                harness.clickContentDescription("重启作用域")
                harness.render()
                harness.clickText("com.xiaomi.bluetooth")
                harness.render()
                val choosing = INTEGRATION_SCOPE_PACKAGES.associateWith { harness.textBounds(it) }
                harness.clickText("重启")
                yield()
                harness.render(60)
                assertEquals(listOf("com.android.bluetooth", "com.milink.service"), requested)
                // Mid-run every row, the unticked one included, sits exactly where its checkbox row was.
                assertTrue(harness.hasText("未选择"))
                assertTrue(harness.hasContentDescription("失败"))
                assertTrue(harness.hasContentDescription("正在重启"))
                assertFalse(harness.hasText("permission denied"))
                assertEquals(choosing, INTEGRATION_SCOPE_PACKAGES.associateWith { harness.textBounds(it) })
                finish.complete(Unit)
                yield()
                harness.render(60)
                assertEquals(1, harness.countText("permission denied"))
                assertTrue(harness.hasContentDescription("已终止，等待系统拉起"))
                assertTrue(harness.hasText("关闭"))
            }
        }
    }

    @Test
    fun materialLandscapeDialogScrollsToLastTargetAndItsFailure() = runOpenRoseUiTest(780, 320) {
        val settings = mutableStateOf(UiSettings(interfaceStyle = InterfaceStyle.MATERIAL3))
        val lastTarget = INTEGRATION_SCOPE_PACKAGES.last()
        val detail = "权限不足\n命令执行失败\n未能重启进程"
        val harness = restartPage(settings) { targets, _ ->
            targets.map { ScopeRestartResult(it, ScopeRestartStatus.FAILED, detail) }
        }
        harness.clickContentDescription("重启作用域")
        harness.render()
        val restartButton = harness.textBounds("重启")
        harness.scrollToText(lastTarget)
        assertTrue(harness.textBounds(lastTarget).height >= 48f)
        assertEquals(restartButton, harness.textBounds("重启"))
        harness.clickText(lastTarget)
        harness.render()
        harness.clickText(lastTarget)
        harness.clickText("重启")
        yield()
        harness.render()

        val closeButton = harness.textBounds("关闭")
        harness.scrollToText(lastTarget)
        val lastResult = harness.allTextBounds(detail).last()
        assertEquals(harness.textBounds(lastTarget), lastResult)
        assertTrue(lastResult.height >= 88f, "the final package and its three lines of detail fit after scrolling")
        assertTrue(lastResult.bottom <= closeButton.top)
        assertEquals(closeButton, harness.textBounds("关闭"))
        harness.clickText("关闭")
        harness.render()
        assertFalse(harness.hasText(detail))
    }

    @Test
    fun failedRestartReportsEachAffectedProcess() = runOpenRoseUiTest(1200, 1000) {
        val page = mutableStateOf("操作")
        val platform = androidTestPlatform(
            MutableStateFlow(hyperOsIntegration),
            restart = { _, _ -> error("权限不足") },
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
        assertEquals(INTEGRATION_SCOPE_PACKAGES.size, harness.countText("权限不足"))
    }

    @Test
    fun failureKeepsResultsAlreadyReported() = runOpenRoseUiTest(1200, 1000) {
        val page = mutableStateOf("操作")
        val platform = androidTestPlatform(MutableStateFlow(hyperOsIntegration), restart = { _, emit ->
            emit(ScopeRestartResult("com.android.bluetooth", ScopeRestartStatus.RESTARTED))
            error("Root 中断")
        }, scope = CoroutineScope(currentCoroutineContext()))
        setContent { AndroidTestPage(FakeOpenRoseModel(), platform, page) }
        val harness = AndroidPageHarness(this, page)
        harness.render()
        harness.clickContentDescription("重启作用域")
        harness.render()
        harness.clickText("重启")
        yield()
        harness.render()
        assertTrue(harness.hasContentDescription("已重启"))
        assertEquals(INTEGRATION_SCOPE_PACKAGES.size - 1, harness.countText("Root 中断"))
    }

    @Test
    fun restartIsUnavailableWithoutASelectedProcess() = runOpenRoseUiTest(1200, 1000) {
        var requests = 0
        val page = mutableStateOf("操作")
        val platform = androidTestPlatform(MutableStateFlow(hyperOsIntegration), restart = { _, _ ->
            requests++
            emptyList()
        }, scope = CoroutineScope(currentCoroutineContext()))
        setContent { AndroidTestPage(FakeOpenRoseModel(), platform, page) }
        val harness = AndroidPageHarness(this, page)
        harness.render()
        harness.clickContentDescription("重启作用域")
        harness.render()
        for (target in INTEGRATION_SCOPE_PACKAGES) harness.clickText(target)
        harness.render()
        assertTrue(harness.hasDisabledButton("重启"))
        assertEquals(0, requests)
        harness.clickText(INTEGRATION_SCOPE_PACKAGES.first())
        harness.render()
        assertFalse(harness.hasDisabledButton("重启"))
    }

    @Test
    fun restartOnlyOffersInstalledScopesForTheChosenPlatformAndOwner() = runOpenRoseUiTest(390, 840) {
        val ordinary = hyperOsIntegration.copy(
            platform = hyperOsIntegration.platform.copy(systemIntegrationApplicability = PlatformApplicability.NOT_APPLICABLE),
        )
        val integration = MutableStateFlow(ordinary)
        val platform = androidTestPlatform(integration)
        val page = mutableStateOf("操作")
        setContent { AndroidTestPage(FakeOpenRoseModel(), platform, page) }
        val harness = AndroidPageHarness(this, page)
        harness.render()
        assertFalse(harness.hasContentDescription("重启作用域"))
        integration.value = ordinary.copy(owner = ordinary.owner.copy(selected = ControlWay.MODULE))
        harness.render()
        harness.clickContentDescription("重启作用域")
        harness.render()
        assertTrue(harness.hasText("com.android.bluetooth"))
        assertFalse(harness.hasText("com.xiaomi.bluetooth"))
        harness.clickText("取消")
        integration.value = hyperOsIntegration.copy(platform = hyperOsIntegration.platform.copy(installedScopes = setOf("com.milink.service")))
        harness.render()
        harness.clickContentDescription("重启作用域")
        harness.render()
        assertTrue(harness.hasText("com.milink.service"))
        assertFalse(harness.hasText("com.android.bluetooth"))
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

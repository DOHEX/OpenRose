@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.HandoffOutcome
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.ModuleAvailability
import io.github.dohex.openrose.core.OwnerFailureStage
import io.github.dohex.openrose.core.OwnerGeneration
import io.github.dohex.openrose.core.OwnerHolder
import io.github.dohex.openrose.core.OwnerReadiness
import io.github.dohex.openrose.core.OwnerState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SwitchRequest
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlWayUiTest {
    private val moduleUnavailable = ModuleAvailability.Unavailable("兼容框架服务尚未连接")

    @Test
    fun defaultStateShowsOrdinaryControlSelectedAndNotesAnUnavailableModule() = runBlocking {
        forEachStyle(ControlState(platform = platform(ModuleAvailability.NotEnabled))) { harness ->
            assertTrue(harness.hasSelectedText("普通控制"))
            assertFalse(harness.hasSelectedText("模块控制"))
            assertTrue(harness.hasText("系统模块未启用。仍可选择模块控制，但模块启用前无法接管控制。"))
            assertFalse(harness.hasText("停用模块控制"))
        }
    }

    @Test
    fun anUnreadyOwnerNeverClaimsToBeReadyWhileTheModuleIsUnavailable() = runBlocking {
        val starting = ControlState(
            platform = platform(moduleUnavailable),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Starting),
        )
        forEachStyle(starting) { harness ->
            assertTrue(harness.hasText("启动中"))
            assertFalse(harness.hasText("就绪"))
            assertTrue(harness.hasText("系统模块暂不可用：兼容框架服务尚未连接。仍可选择模块控制，但模块可用前无法接管控制。"))
        }
        val moduleStarting = starting.copy(owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Starting))
        forEachStyle(moduleStarting) { harness ->
            assertTrue(harness.hasSelectedText("模块控制"))
            assertFalse(harness.hasText("就绪"))
        }
    }

    @Test
    fun unavailableModuleOwnerShowsStageAndReasonWithoutReadiness() = runBlocking {
        val state = ControlState(
            platform = platform(moduleUnavailable),
            owner = OwnerState(
                selected = ControlWay.MODULE,
                readiness = OwnerReadiness.Unavailable(OwnerFailureStage.DISCOVERY, "未找到系统蓝牙中的模块控制端"),
            ),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasSelectedText("模块控制"))
            assertTrue(harness.hasText("不可用"))
            assertTrue(harness.hasText("发现控制端"))
            assertTrue(harness.hasText("未找到系统蓝牙中的模块控制端"))
            assertFalse(harness.hasText("就绪"))
            assertTrue(harness.hasText("未连接"))
        }
    }

    @Test
    fun readyConnectedModuleShowsSelectionOwnerAndConnectionAsSeparateLinesAndTheRealHolder() = runBlocking {
        val holder = OwnerHolder(
            way = ControlWay.MODULE,
            generation = OwnerGeneration("boot-7", 3),
            packageName = "com.android.bluetooth",
            processName = "com.android.bluetooth",
            uid = 1002,
            pid = 4321,
        )
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            connection = ConnectionState.Connected(DeviceId("ctl-way-i5"), 1L, ModelProfile.I5_ZT, ConnectionPhase.READY),
            owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready, holder = holder),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasText("已选择方式"))
            assertTrue(harness.hasText("所有者状态"))
            assertTrue(harness.hasText("耳机控制连接"))
            assertTrue(harness.hasText("就绪"))
            assertTrue(harness.hasText("已连接 · i5 ZT"))
            assertTrue(harness.hasText("com.android.bluetooth"))
            assertTrue(harness.hasText("boot-7#3"))
            assertTrue(harness.hasText("1002"))
            assertTrue(harness.hasText("4321"))
        }
    }

    @Test
    fun readyOwnerWithoutAnEarphoneConnectionStillReportsTheConnectionSeparately() = runBlocking {
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            connection = ConnectionState.Connecting(DeviceId("ctl-way-i5"), 1L, ConnectionPhase.CONNECTING),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasText("就绪"))
            assertTrue(harness.hasText("连接中"))
            assertFalse(harness.hasText("未连接"))
        }
    }

    @Test
    fun selectingAnotherWayCallsOnceAndReselectingTheSelectedWayDoesNothing() = runBlocking {
        val state = ControlState(
            platform = platform(ModuleAvailability.NotEnabled),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Ready),
        )
        forEachStyle(state) { harness ->
            harness.clickText("普通控制")
            harness.render()
            assertTrue(harness.selections.isEmpty(), "reselecting the selected way must not call back")
            harness.clickText("模块控制")
            harness.render()
            assertEquals(listOf(ControlWay.MODULE), harness.selections)
            assertTrue(harness.disables == 0)
        }
    }

    @Test
    fun disableModuleControlIsOfferedOnlyWhileModuleControlIsSelected() = runBlocking {
        val ordinary = ControlState(platform = platform(ModuleAvailability.Available))
        forEachStyle(ordinary) { harness -> assertFalse(harness.hasText("停用模块控制")) }

        val none = ordinary.copy(owner = OwnerState(selected = ControlWay.NONE, readiness = OwnerReadiness.Idle("模块控制已停用")))
        forEachStyle(none) { harness -> assertFalse(harness.hasText("停用模块控制")) }

        val module = ordinary.copy(owner = OwnerState(selected = ControlWay.MODULE, readiness = OwnerReadiness.Ready))
        forEachStyle(module) { harness ->
            assertTrue(harness.hasText("停用模块控制"))
            harness.clickText("停用模块控制")
            harness.render()
            assertEquals(1, harness.disables)
            assertTrue(harness.selections.isEmpty())
        }
    }

    @Test
    fun blockedSwitchExplainsNothingWasTakenOverAndKeepsBothChoices() = runBlocking {
        val request = SwitchRequest("req-1", ControlWay.MODULE, ControlWay.ORDINARY, OwnerGeneration("boot-7", 3))
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(
                selected = ControlWay.ORDINARY,
                readiness = OwnerReadiness.SwitchBlocked(request, OwnerFailureStage.RELEASE, "尚未确认模块已释放控制连接"),
            ),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasText("切换未完成"))
            assertTrue(harness.hasText("确认释放"))
            assertTrue(harness.hasText("尚未确认模块已释放控制连接"))
            assertTrue(harness.hasText("无法确认上一个所有者已释放控制连接，为避免两个所有者同时连接，没有进行接管。"))
            assertTrue(harness.hasClickableText("普通控制"))
            assertTrue(harness.hasClickableText("模块控制"))
            assertFalse(harness.hasText("就绪"))
        }
    }

    @Test
    fun blockedHandoffShowsItsStageAndReason() = runBlocking {
        val request = SwitchRequest("req-2", ControlWay.ORDINARY, ControlWay.MODULE)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(
                selected = ControlWay.ORDINARY,
                readiness = OwnerReadiness.Ready,
                lastHandoff = HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "无法保存控制方式，未做任何切换"),
            ),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasText("上次切换"))
            assertTrue(harness.hasText("保存控制方式"))
            assertTrue(harness.hasText("无法保存控制方式，未做任何切换"))
        }
    }

    @Test
    fun switchingDisablesTheChoices() = runBlocking {
        val request = SwitchRequest("req-3", ControlWay.ORDINARY, ControlWay.MODULE)
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.ORDINARY, readiness = OwnerReadiness.Switching(request)),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasText("切换中"))
            assertTrue(harness.hasDisabledText("普通控制"))
            assertTrue(harness.hasDisabledText("模块控制"))
            assertFalse(harness.hasText("就绪"))
        }
    }

    @Test
    fun disabledModuleControlSaysSoAndStillOffersBothChoices() = runBlocking {
        val state = ControlState(
            platform = platform(ModuleAvailability.Available),
            owner = OwnerState(selected = ControlWay.NONE, readiness = OwnerReadiness.Idle("模块控制已停用")),
        )
        forEachStyle(state) { harness ->
            assertTrue(harness.hasText("模块控制已停用，未启用普通控制"))
            assertFalse(harness.hasSelectedText("普通控制"))
            assertFalse(harness.hasSelectedText("模块控制"))
            harness.clickText("普通控制")
            harness.render()
            assertEquals(listOf(ControlWay.ORDINARY), harness.selections)
            harness.clickText("模块控制")
            harness.render()
            assertEquals(listOf(ControlWay.ORDINARY, ControlWay.MODULE), harness.selections)
        }
    }

    private fun platform(module: ModuleAvailability) = PlatformState(
        permission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        module = module,
        hostPlatform = HostPlatform.ANDROID,
        systemIntegrationApplicability = PlatformApplicability.APPLICABLE,
        controlTransportApplicability = PlatformApplicability.APPLICABLE,
    )

    private suspend fun forEachStyle(base: ControlState, check: suspend (Harness) -> Unit) {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(base.copy(settings = UserSettings(interfaceStyle = style)))
            try {
                harness.render()
                harness.clickTab("系统集成")
                harness.render()
                check(harness)
            } catch (failure: Throwable) {
                throw AssertionError("style $style: ${failure.message}", failure)
            } finally {
                harness.close()
            }
        }
    }

    private suspend fun createHarness(initial: ControlState): Harness {
        val state = mutableStateOf(initial)
        val selections = mutableListOf<ControlWay>()
        var disables = 0
        val scene = createOpenRoseUiTestScene(1200, 1000) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = {},
                onRequestPermission = {},
                onOpenSystemSettings = {},
                onStartScan = {},
                onStopScan = {},
                onConnect = {},
                onDisconnect = {},
                onSet = { _, _ -> },
                onAddDeviceConnect = { error("Unexpected add-device connection in this scenario") },
                onSelectControlWay = { selections += it },
                onDisableModuleControl = { disables++ },
            )
        }
        return Harness(scene, state, selections) { disables }
    }

    private class Harness(
        scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val selections: List<ControlWay>,
        private val disableCount: () -> Int,
    ) : OpenRoseUiTestHarness(scene) {
        val disables: Int get() = disableCount()
    }
}

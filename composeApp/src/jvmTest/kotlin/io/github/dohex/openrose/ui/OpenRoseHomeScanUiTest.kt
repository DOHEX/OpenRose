@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Constraints
import io.github.dohex.openrose.core.Appearance
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.InterfaceStyle
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseHomeScanUiTest {
    @Test
    fun recognizedHeadsetsStayConnectableWhileUnsupportedDevicesAreInHelp() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val headset = recognizedCandidate(DeviceId("recognized-i5")).copy(name = null)
            val others = (1..30).map { index ->
                headset.copy(deviceId = DeviceId("other-$index"), name = "其他设备 $index", profile = null)
            }
            val harness = createHarness(420, 1200, state(style).copy(candidates = others + headset))
            try {
                harness.render()
                harness.openAddDevice()
                assertTrue(harness.hasText("EarFree i5"))
                assertFalse(harness.hasText("其他设备 1"))
                assertEquals(1, harness.countText("连接"))
                harness.clickText("找不到耳机？")
                harness.render()
                assertTrue(harness.hasText("其他蓝牙设备"))
                assertTrue(harness.hasText("其他设备 1"))
                assertEquals(1, harness.countText("连接"), "unsupported devices cannot start control connections")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun resizingDisconnectedHomeDoesNotEmitCallbacks() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(1280, 1000, state(style))
            try {
                harness.render()
                for (width in listOf(420, 1280)) {
                    harness.resize(width, 1000)
                    harness.render()
                    assertTrue(harness.hasTab(if (width < 900) "耳机" else "我的耳机"),
                        "navigation should reflect the resized width in $style")
                    assertTrue(harness.permissionRequests.isEmpty(), "resizing must not request permission in $style")
                    assertTrue(harness.systemSettingsOpens.isEmpty(), "resizing must not open settings in $style")
                    assertTrue(harness.startScans.isEmpty() && harness.stopScans.isEmpty(), "resizing must not change scanning in $style")
                    assertTrue(harness.connects.isEmpty() && harness.disconnects.isEmpty(), "resizing must not change connection in $style")
                    assertTrue(harness.settingWrites.isEmpty(), "resizing must not send device settings in $style")
                    assertTrue(harness.settingsChanges.isEmpty(), "resizing must not save preferences in $style")
                }
            } finally {
                harness.close()
            }
        }
    }



    @Test
    fun unavailableTransportShowsReasonAndDetailsWithoutConnectionEntrances() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val reason = "此平台没有耳机控制传输"
            val harness = createHarness(
                420,
                900,
                state(style).copy(platform = PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Unavailable(reason))),
            )
            try {
                harness.render()
                assertTrue(harness.hasText(reason), "the host's reason should be visible in $style")
                assertTrue(harness.hasClickableText("查看连接与运行状态"))
                assertFalse(harness.hasContentDescription("添加耳机"))
                assertFalse(harness.hasText("搜索并添加耳机"))
                assertFalse(harness.hasText("停止扫描"))

                harness.state.value = harness.state.value.copy(
                    candidates = listOf(recognizedCandidate(DeviceId("previously-discovered"))),
                )
                harness.render()
                assertFalse(harness.hasText("ROSE i5"))
                assertFalse(harness.hasText("停止扫描"))
                assertTrue(harness.stopScans.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun activeScanCanBeStoppedWhenTransportBecomesUnavailable() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(420, 900, state(style))
            try {
                harness.render()
                harness.state.value = harness.state.value.copy(
                    scan = ScanState.Scanning,
                    candidates = listOf(recognizedCandidate(DeviceId("previously-discovered"))),
                )
                harness.render()

                val reason = "蓝牙扫描所需的定位服务已关闭"
                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Unavailable(reason)),
                )
                harness.render()
                assertTrue(harness.hasText(reason))
                assertTrue(harness.hasText("正在寻找耳机…"))
                assertTrue(harness.hasClickableText("停止扫描"))
                assertFalse(harness.hasContentDescription("添加耳机"))
                assertFalse(harness.hasText("附近设备"))
                assertFalse(harness.hasText("ROSE i5"))
                assertEquals(0, harness.startScans.size)
                assertTrue(harness.permissionRequests.isEmpty())
                assertTrue(harness.systemSettingsOpens.isEmpty())
                assertTrue(harness.connects.isEmpty())
                assertTrue(harness.disconnects.isEmpty())
                assertTrue(harness.settingWrites.isEmpty())
                assertTrue(harness.stopScans.isEmpty())

                harness.clickText("停止扫描")
                assertEquals(1, harness.stopScans.size)
                assertEquals(0, harness.startScans.size)
                harness.state.value = harness.state.value.copy(scan = ScanState.Idle)
                harness.render()
                assertFalse(harness.hasText("停止扫描"))
                assertFalse(harness.hasText("正在寻找耳机…"))
                assertTrue(harness.hasClickableText("查看连接与运行状态"))
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun missingPermissionTakesPrecedenceOverAnUnavailableTransport() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for (permission in listOf(PermissionState.REQUIRED, PermissionState.DENIED)) {
                val reason = "授予权限后才能检查传输"
                val harness = createHarness(
                    420,
                    900,
                    state(style).copy(platform = PlatformState(permission, TransportAvailability.Unavailable(reason))),
                )
                try {
                    harness.render()
                    harness.openAddDevice()
                    assertTrue(harness.hasText("需要蓝牙权限"))
                    assertTrue(harness.hasClickableText("授予蓝牙权限"))
                    assertFalse(harness.hasContentDescription("添加耳机"))
                    assertFalse(harness.hasText("附近设备"))
                    assertFalse(harness.hasText("停止搜索"))
                    assertFalse(harness.hasText("重新搜索"))
                    assertFalse(harness.hasText(reason), "the permission recovery is the current reason in $style")
                    harness.clickText("授予蓝牙权限")
                    assertEquals(1, harness.permissionRequests.size)
                    assertTrue(harness.startScans.isEmpty())
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun disconnectedHomeShowsManualScanAndCanStopItInBothStylesAndLayouts() = runBlocking {
        for (style in InterfaceStyle.entries) {
            for ((width, height) in listOf(420 to 900, 1200 to 1000)) {
                val harness = createHarness(width, height, state(style))
                try {
                    harness.render()

                    assertTrue(harness.hasText("搜索并添加耳机"))
                    assertTrue(harness.hasContentDescription("添加耳机"))
                    assertTrue(harness.startScans.isEmpty(), "rendering and navigation must not start a scan")
                    assertTrue(harness.connects.isEmpty())
                    assertTrue(harness.settingWrites.isEmpty())

                    harness.clickTab("设置")
                    harness.render()
                    harness.clickText("组件风格")
                    harness.render()
                    harness.clickText(if (style == InterfaceStyle.MIUIX) "Material 3" else "Miuix")
                    harness.render()
                    harness.clickTab(if (width >= 900) "我的耳机" else "耳机")
                    harness.render()
                    assertTrue(harness.startScans.isEmpty(), "style changes must not start scanning")
                    assertTrue(harness.connects.isEmpty(), "style changes must not reconnect")
                    assertTrue(harness.settingWrites.isEmpty(), "style changes must not send device settings")
                    harness.openAddDevice()
                    harness.state.value = harness.state.value.copy(scan = ScanState.Scanning)
                    harness.render()
                    assertTrue(harness.hasClickableText("停止搜索"))
                    harness.clickText("停止搜索")
                    assertTrue(harness.stopScans.isNotEmpty())
                    assertTrue(harness.settingWrites.isEmpty())
                } finally {
                    harness.close()
                }
            }
        }
    }

    @Test
    fun disconnectedHomeOffersOnlyAvailablePlatformRecoveryActions() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(
                420,
                900,
                state(style).copy(platform = PlatformState(PermissionState.REQUIRED, TransportAvailability.Available)),
            )
            try {
                harness.render()
                harness.openAddDevice()
                assertTrue(harness.hasText("需要蓝牙权限"))
                assertTrue(harness.hasText("授予蓝牙权限"))
                assertFalse(harness.hasText("附近设备"))
                assertFalse(harness.hasText("重新搜索"))
                assertFalse(harness.hasText("停止搜索"))
                harness.clickText("授予蓝牙权限")
                assertEquals(1, harness.permissionRequests.size)
                assertTrue(harness.startScans.isEmpty())

                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Disabled),
                )
                harness.render()
                assertTrue(harness.hasText("系统蓝牙已关闭"))
                assertTrue(harness.hasText("打开蓝牙设置"))
                assertFalse(harness.hasText("附近设备"))
                assertFalse(harness.hasText("重新搜索"))
                assertFalse(harness.hasText("停止搜索"))
                harness.clickText("打开蓝牙设置")
                assertEquals(1, harness.systemSettingsOpens.size)
                assertTrue(harness.startScans.isEmpty())

                val unavailableReason = "当前平台未提供耳机控制传输"
                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Unavailable(unavailableReason)),
                )
                harness.render()
                assertTrue(harness.hasText(unavailableReason))
                assertFalse(harness.hasText("附近设备"))
                assertFalse(harness.hasText("重新搜索"))
                assertFalse(harness.hasText("停止搜索"))
                assertEquals(1, harness.permissionRequests.size)
                assertEquals(1, harness.systemSettingsOpens.size)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun unavailableHomeOpensDetailsAndRestoresManualActionsWhenTransportBecomesAvailable() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val reason = "主机报告的实际传输原因"
            val harness = createHarness(
                420,
                900,
                state(style).copy(platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Unavailable(reason))),
            )
            try {
                harness.render()
                harness.focus("查看连接与运行状态")
                harness.sendKey(Key.Enter)
                harness.render()
                assertTrue(harness.hasText("当前连接"), "the visible action opens existing diagnostics in $style")
                assertTrue(harness.hasText(reason))
                harness.sendKey(Key.Escape)
                harness.render()
                assertTrue(harness.hasText("查看连接与运行状态"))

                harness.clickTab("设置")
                harness.render()
                harness.clickText("组件风格")
                harness.render()
                harness.clickText(if (style == InterfaceStyle.MIUIX) "Material 3" else "Miuix")
                harness.render()
                harness.clickTab("耳机")
                harness.render()
                assertTrue(harness.hasText(reason))
                assertFalse(harness.hasContentDescription("添加耳机"))

                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                )
                harness.render()
                assertTrue(harness.hasContentDescription("添加耳机"))
                assertTrue(harness.hasText("搜索并添加耳机"))
                harness.openAddDevice()
                assertEquals(1, harness.startScans.size)
                harness.clickContentDescription("返回")
                harness.render()

                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Unavailable(reason)),
                )
                harness.render()
                assertFalse(harness.hasContentDescription("添加耳机"))
                harness.focus("查看连接与运行状态")
                harness.sendKey(Key.Tab, shift = true)
                harness.sendKey(Key.Tab)
                harness.sendKey(Key.Spacebar)
                harness.render()
                assertTrue(harness.hasText("当前连接"), "the remaining action is reachable by keyboard in $style")
                assertEquals(1, harness.startScans.size)
                assertTrue(harness.connects.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun unknownModelCandidateHasNoConnectAction() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val candidate = DeviceCandidate(
                deviceId = DeviceId("unknown-model"),
                name = "Nearby headset",
                rssi = -48,
                profile = null,
                manufacturerDataHex = "",
            )
            val harness = createHarness(420, 900, state(style).copy(candidates = listOf(candidate)))
            try {
                harness.render()
                harness.openAddDevice()
                assertFalse(harness.hasText("Nearby headset"))
                harness.clickText("找不到耳机？")
                harness.render()
                assertTrue(harness.hasText("其他蓝牙设备"))
                assertTrue(harness.hasText("Nearby headset"))
                assertTrue(harness.hasText("暂不支持"))
                assertFalse(harness.hasClickableText("连接"))
                assertTrue(harness.connects.isEmpty())
            } finally {
                harness.close()
            }
        }
    }


    @Test
    fun readyConnectionReplacesNearbyDevicesOnTheSameHomePage() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val id = DeviceId("ready-i5")
            val profile = ModelProfile.I5_ZT
            val device = DeviceState(
                deviceId = id,
                sessionId = SESSION_ID,
                profile = profile,
                capabilities = DeviceCapabilities(readableFields = emptySet()),
                fields = emptyMap(),
            )
            val harness = createHarness(420, 900, state(style).copy(candidates = listOf(recognizedCandidate(id))))
            try {
                harness.render()
                harness.state.value = harness.state.value.copy(
                    connection = ConnectionState.Connected(id, SESSION_ID, profile, ConnectionPhase.READY),
                    device = device,
                )
                harness.render()
                assertTrue(harness.hasText("设备详情"))
                assertFalse(harness.hasText("附近设备"))
                assertTrue(harness.startScans.isEmpty())
                assertTrue(harness.connects.isEmpty())
                assertTrue(harness.settingWrites.isEmpty())

                harness.state.value = harness.state.value.copy(connection = ConnectionState.Disconnected, device = null)
                harness.render()
                assertTrue(harness.hasText("搜索并添加耳机"))
                assertFalse(harness.hasText("设备详情"))
            } finally {
                harness.close()
            }
        }
    }

    private fun recognizedCandidate(id: DeviceId) = DeviceCandidate(
        deviceId = id,
        name = "ROSE i5",
        rssi = -52,
        profile = ModelProfile.I5_ZT,
        manufacturerDataHex = "",
    )

    private suspend fun createHarness(width: Int, height: Int, initialState: ControlState): ScanHarness {
        val state = mutableStateOf(initialState)
        val startScans = mutableListOf<Unit>()
        val stopScans = mutableListOf<Unit>()
        val permissionRequests = mutableListOf<Unit>()
        val systemSettingsOpens = mutableListOf<Unit>()
        val connects = mutableListOf<DeviceId>()
        val disconnects = mutableListOf<Unit>()
        val settingWrites = mutableListOf<Pair<io.github.dohex.openrose.core.DeviceSetting, FieldValue>>()
        val settingsChanges = mutableListOf<UserSettings>()
        val scene = createOpenRoseUiTestScene(width, height) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings ->
                    settingsChanges += settings
                    state.value = state.value.copy(settings = settings)
                },
                onRequestPermission = { permissionRequests += Unit },
                onOpenSystemSettings = { systemSettingsOpens += Unit },
                onStartScan = {
                    startScans += Unit
                    state.value = state.value.copy(scan = ScanState.Scanning)
                },
                onStopScan = {
                    stopScans += Unit
                    state.value = state.value.copy(scan = ScanState.Idle)
                },
                onConnect = { connects += it },
                onDisconnect = { disconnects += Unit },
                onSet = { setting, value -> settingWrites += setting to value },
                onAddDeviceConnect = { id ->
                    connects += id
                    io.github.dohex.openrose.core.OperationResult.Failure(ControlError(ControlErrorCode.FAILED, "测试连接失败"))
                },
            )
        }
        return ScanHarness(
            scene,
            state,
            startScans,
            stopScans,
            permissionRequests,
            systemSettingsOpens,
            connects,
            disconnects,
            settingWrites,
            settingsChanges,
        )
    }

    private fun state(style: InterfaceStyle) = ControlState(
        platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
        settings = UserSettings(interfaceStyle = style, appearance = Appearance.SYSTEM),
    )

    private class ScanHarness(
        scene: ImageComposeScene,
        val state: MutableState<ControlState>,
        val startScans: MutableList<Unit>,
        val stopScans: MutableList<Unit>,
        val permissionRequests: MutableList<Unit>,
        val systemSettingsOpens: MutableList<Unit>,
        val connects: MutableList<DeviceId>,
        val disconnects: MutableList<Unit>,
        val settingWrites: MutableList<Pair<io.github.dohex.openrose.core.DeviceSetting, FieldValue>>,
        val settingsChanges: MutableList<UserSettings>,
    ) : OpenRoseUiTestHarness(scene) {
        fun resize(width: Int, height: Int) {
            scene.constraints = Constraints.fixed(width, height)
        }
        suspend fun openAddDevice() {
            clickContentDescription("添加耳机")
            render()
        }
    }

    private companion object {
        const val SESSION_ID = 35L
    }
}

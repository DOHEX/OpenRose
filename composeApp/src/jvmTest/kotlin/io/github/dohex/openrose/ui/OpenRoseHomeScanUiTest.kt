@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
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
import kotlin.math.abs

class OpenRoseHomeScanUiTest {
    @Test
    fun emptyNearbyMessageUsesThePageSurface() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(1280, 1000, state(style))
            try {
                harness.render()
                val message = harness.textBounds("还没有发现耳机。请确认耳机已开启并在附近。")
                val details = harness.clickableBounds("连接与运行状态")
                val image = harness.snapshot()
                val y = message.center.y.toInt()
                assertEquals(image.getRGB(details.right.toInt() + 24, y), image.getRGB(details.right.toInt() - 24, y),
                    "the empty message should use the surrounding page surface in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun disconnectedHomeStartsNearTheTopBar() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(1280, 1000, state(style))
            try {
                harness.render()
                val action = harness.clickableContentDescriptionBounds("添加耳机")
                val title = harness.textBounds("连接你的 ROSE 耳机")
                assertTrue(title.top - action.bottom < action.height,
                    "home content should begin near the top bar in $style: action=$action title=$title")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun disconnectedHomeKeepsACompactCenteredColumnOnWideScreens() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(1280, 1000, state(style))
            try {
                harness.render()
                val title = harness.textBounds("连接你的 ROSE 耳机")
                val instruction = harness.textBounds("让耳机保持开启，并靠近手机")
                val nearby = harness.textBounds("附近设备")
                val scan = harness.clickableBounds("扫描耳机")
                val details = harness.clickableBounds("连接与运行状态")
                assertTrue(details.width < 1280f * 2 / 3, "content should stay compact in $style: $details")
                assertTrue(abs(details.center.x - 1280f / 2) < 1280f / 10, "content should be centered in $style: $details")
                assertTrue(listOf(instruction.left, nearby.left, scan.left).all { abs(it - title.left) < 32f },
                    "the column should read from a common left edge in $style")
                assertTrue(title.top < instruction.top && instruction.top < nearby.top && nearby.top < scan.top,
                    "instruction, nearby devices and scan should follow the title in $style")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun disconnectedHomeUsesARegularWidthScanActionOnWideScreens() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val harness = createHarness(1280, 1000, state(style))
            try {
                harness.render()
                val scan = harness.clickableBounds("扫描耳机")
                assertTrue(scan.width < 1280f / 3, "scan action should not span the wide content area in $style: $scan")
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
                assertFalse(harness.hasText("附近设备"))
                assertFalse(harness.hasText("还没有发现耳机。请确认耳机已开启并在附近。"))
                assertFalse(harness.hasText("扫描耳机"))

                harness.state.value = harness.state.value.copy(
                    scan = ScanState.Scanning,
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
                    assertTrue(harness.hasText("需要蓝牙权限"))
                    assertTrue(harness.hasClickableText("授予蓝牙权限"))
                    assertTrue(harness.hasContentDescription("添加耳机"))
                    assertTrue(harness.hasText("附近设备"))
                    assertTrue(harness.hasDisabledButton("扫描耳机"))
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

                    assertTrue(harness.hasText("附近设备"), "home should show nearby devices in $style at ${width}dp")
                    assertTrue(harness.hasText("扫描耳机"), "home should offer manual scanning in $style at ${width}dp")
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

                    harness.clickText("扫描耳机")
                    assertEquals(1, harness.startScans.size)
                    harness.state.value = harness.state.value.copy(scan = ScanState.Scanning)
                    harness.render()
                    assertTrue(harness.hasText("正在寻找耳机…"))
                    harness.clickText("停止扫描")
                    assertEquals(1, harness.stopScans.size)
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
                assertTrue(harness.hasText("需要蓝牙权限"))
                assertTrue(harness.hasText("授予蓝牙权限"))
                assertTrue(harness.hasDisabledButton("扫描耳机"))
                harness.clickText("授予蓝牙权限")
                assertEquals(1, harness.permissionRequests.size)
                assertTrue(harness.startScans.isEmpty())

                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Disabled),
                )
                harness.render()
                assertTrue(harness.hasText("系统蓝牙已关闭"))
                assertTrue(harness.hasText("打开蓝牙设置"))
                assertTrue(harness.hasDisabledButton("扫描耳机"))
                harness.clickText("打开蓝牙设置")
                assertEquals(1, harness.systemSettingsOpens.size)
                assertTrue(harness.startScans.isEmpty())

                val unavailableReason = "当前平台未提供耳机控制传输"
                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.NOT_REQUIRED, TransportAvailability.Unavailable(unavailableReason)),
                )
                harness.render()
                assertTrue(harness.hasText(unavailableReason))
                assertFalse(harness.hasContentDescription("添加耳机"))
                assertFalse(harness.hasText("附近设备"))
                assertFalse(harness.hasText("还没有发现耳机。请确认耳机已开启并在附近。"))
                assertFalse(harness.hasText("扫描耳机"))
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
                assertFalse(harness.hasText("附近设备"))

                harness.state.value = harness.state.value.copy(
                    platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                )
                harness.render()
                assertTrue(harness.hasContentDescription("添加耳机"))
                assertTrue(harness.hasText("附近设备"))
                assertTrue(harness.hasClickableText("扫描耳机"))
                assertTrue(harness.permissionRequests.isEmpty())
                assertTrue(harness.systemSettingsOpens.isEmpty())
                assertTrue(harness.startScans.isEmpty())
                assertTrue(harness.connects.isEmpty())
                assertTrue(harness.settingWrites.isEmpty())
                harness.clickText("扫描耳机")
                assertEquals(1, harness.startScans.size)

                harness.focus("添加耳机")
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
                assertTrue(harness.hasText("未知型号配置，无法连接"))
                assertFalse(harness.hasClickableText("连接"))
                assertTrue(harness.connects.isEmpty())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun recognizedCandidateShowsConnectionProgressCancellationAndRetry() = runBlocking {
        for (style in InterfaceStyle.entries) {
            val id = DeviceId("recognized-i5")
            val candidate = recognizedCandidate(id)
            val harness = createHarness(1200, 1000, state(style).copy(candidates = listOf(candidate)))
            try {
                harness.render()
                assertTrue(harness.hasText("已识别 · ZT · -52 dBm"))
                harness.clickText("连接")
                assertEquals(listOf(id), harness.connects)
                assertTrue(harness.settingWrites.isEmpty())

                harness.state.value = harness.state.value.copy(
                    connection = ConnectionState.Connecting(id, SESSION_ID, ConnectionPhase.DISCOVERING),
                )
                harness.render()
                assertTrue(harness.hasText("正在发现耳机服务…"))
                assertTrue(harness.hasText("取消连接"))
                harness.clickText("取消连接")
                assertEquals(1, harness.disconnects.size)
                assertEquals(listOf(id), harness.connects, "rendering the in-progress state must not reconnect")

                harness.state.value = harness.state.value.copy(
                    connection = ConnectionState.Disconnected,
                    lastError = ControlError(ControlErrorCode.FAILED, "耳机服务发现失败"),
                )
                harness.render()
                assertTrue(harness.hasText("耳机服务发现失败"))
                assertEquals(1, harness.countText("耳机服务发现失败"), "connection failure appears once")
                assertTrue(harness.hasClickableText("查看详情"))
                harness.clickText("查看详情")
                harness.render()
                assertTrue(harness.hasText("当前连接"), "home error opens diagnostics")
                harness.clickTab("我的耳机")
                harness.render()
                harness.clickText("扫描耳机")
                assertEquals(1, harness.startScans.size)

                val scanFailure = ControlError(ControlErrorCode.TIMEOUT, "附近设备扫描超时")
                harness.state.value = harness.state.value.copy(scan = ScanState.Failed(scanFailure), lastError = scanFailure)
                harness.render()
                assertTrue(harness.hasText("扫描未完成：附近设备扫描超时"))
                assertEquals(1, harness.countText("扫描未完成：附近设备扫描超时"), "scan failure appears once")
                assertTrue(harness.hasClickableText("查看详情"))
                harness.clickText("查看详情")
                harness.render()
                assertTrue(harness.hasText("当前连接"), "scan failure opens diagnostics")
                harness.clickTab("我的耳机")
                harness.render()
                harness.clickText("重新扫描")
                assertEquals(2, harness.startScans.size)
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

                harness.state.value = harness.state.value.copy(connection = ConnectionState.Disconnected)
                harness.render()
                assertTrue(harness.hasText("附近设备"))
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
        val scene = createOpenRoseUiTestScene(width, height) {
            OpenRoseApp(
                state = state.value,
                onSettingsChange = { settings -> state.value = state.value.copy(settings = settings) },
                onRequestPermission = { permissionRequests += Unit },
                onOpenSystemSettings = { systemSettingsOpens += Unit },
                onStartScan = { startScans += Unit },
                onStopScan = { stopScans += Unit },
                onConnect = { connects += it },
                onDisconnect = { disconnects += Unit },
                onSet = { setting, value -> settingWrites += setting to value },
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
    ) : OpenRoseUiTestHarness(scene)

    private companion object {
        const val SESSION_ID = 35L
    }
}

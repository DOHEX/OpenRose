@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
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
                assertTrue(harness.hasDisabledButton("扫描耳机"))
                assertEquals(1, harness.permissionRequests.size)
                assertEquals(1, harness.systemSettingsOpens.size)
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

package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import androidx.compose.ui.input.key.Key
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddDeviceLifecycleUiTest {
    @Test
    fun manualStopAndScanFailureKeepDiscoveredHeadsetsUsable() {
        for (style in InterfaceStyle.entries) {
            for (failScan in listOf(false, true)) {
                withAddDeviceHarness(style) { harness ->
                    harness.openAddDevice()
                    if (failScan) {
                        val error = ControlError(ControlErrorCode.FAILED, "蓝牙搜索中断")
                        harness.state.value = harness.state.value.copy(scan = ScanState.Failed(error), lastError = error)
                    } else {
                        harness.clickText("停止搜索")
                    }
                    harness.render()
                    assertTrue(harness.hasClickableText("重新搜索"))
                    assertTrue(harness.hasClickableText("连接"))
                    assertEquals(failScan, harness.hasText("蓝牙搜索中断"))
                    harness.clickText("连接")
                    harness.render()
                    assertTrue(harness.hasClickableText("取消连接"))
                    assertIs<ConnectionState.Connecting>(harness.state.value.connection)
                }
            }
        }
    }

    @Test
    fun scanExpiresAfterTwentySecondsRetainsResultsAndRestartGetsANewDeadline() {
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style) { harness ->
                harness.openAddDevice()
                val firstDeadline = harness.scanStartTimes.single() + 20_000
                harness.advanceTimeBy(firstDeadline - harness.currentTime - 100)
                harness.render(frames = 1)
                assertTrue(harness.hasClickableText("停止搜索"))
                assertEquals(ScanState.Scanning, harness.state.value.scan)

                harness.advanceTimeBy(100)
                harness.render()
                assertEquals(ScanState.Idle, harness.state.value.scan)
                assertTrue(harness.hasClickableText("重新搜索"))
                assertTrue(harness.hasText(requireNotNull(addDeviceCandidate.name)))
                assertTrue(harness.hasClickableText("连接"), "expiry must preserve usable results")
                assertEquals(null, harness.state.value.lastError, "normal expiry is not a scan failure")

                harness.clickText("重新搜索")
                harness.render()
                assertEquals(2, harness.scanStarts)
                harness.advanceTimeBy(10_000)
                harness.render()
                assertTrue(harness.hasClickableText("停止搜索"), "a new round needs its own twenty seconds")
                val secondDeadline = harness.scanStartTimes.last() + 20_000
                harness.advanceTimeBy(secondDeadline - harness.currentTime)
                harness.render()
                assertEquals(ScanState.Idle, harness.state.value.scan)
                assertTrue(harness.hasClickableText("重新搜索"))
            }
        }
    }

    @Test
    fun styleSwitchAndAdvertisementUpdateDoNotRestartTheScanTimer() {
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style) { harness ->
                harness.openAddDevice()
                val deadline = harness.scanStartTimes.single() + 20_000
                harness.advanceTimeBy(10_000)
                harness.state.value = harness.state.value.copy(
                    candidates = persistentListOf(addDeviceCandidate.copy(rssi = -31)),
                )
                harness.uiSettings.value = harness.uiSettings.value.copy(
                    interfaceStyle = if (style == InterfaceStyle.MIUIX) InterfaceStyle.MATERIAL3 else InterfaceStyle.MIUIX,
                )
                harness.render()
                assertTrue(harness.hasClickableText("停止搜索"))
                assertEquals(1, harness.scanStarts)
                assertEquals(0, harness.scanStops)
                harness.advanceTimeBy(deadline - harness.currentTime)
                harness.render()
                assertEquals(ScanState.Idle, harness.state.value.scan)
                assertTrue(harness.hasClickableText("重新搜索"))
                assertEquals(1, harness.scanStarts, "style changes and broadcasts must not extend the round")
            }
        }
    }

    @Test
    fun losingPermissionOrBluetoothStopsSearchAndRecoveryStartsANewRound() {
        for (style in InterfaceStyle.entries) {
            for (unavailable in listOf(
                PlatformState(PermissionState.DENIED, TransportAvailability.Available),
                PlatformState(PermissionState.GRANTED, TransportAvailability.Disabled),
            )) {
                withAddDeviceHarness(style) { harness ->
                    harness.openAddDevice()
                    harness.state.value = harness.state.value.copy(platform = unavailable)
                    harness.render()
                    assertEquals(ScanState.Idle, harness.state.value.scan)
                    assertFalse(harness.hasText("停止搜索"))
                    assertFalse(harness.hasText("重新搜索"))
                    assertFalse(harness.hasText("附近设备"))
                    assertFalse(harness.hasText("连接"))
                    assertTrue(harness.hasClickableText(
                        if (unavailable.permission == PermissionState.DENIED) "授予蓝牙权限" else "打开蓝牙设置",
                    ))
                    harness.advanceTimeBy(30_000)
                    harness.state.value = harness.state.value.copy(
                        platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
                    )
                    harness.render()
                    assertTrue(harness.hasClickableText("停止搜索"))
                    assertEquals(ScanState.Scanning, harness.state.value.scan)
                    assertEquals(2, harness.scanStarts)
                    harness.advanceTimeBy(10_000)
                    harness.render()
                    assertEquals(ScanState.Scanning, harness.state.value.scan, "blocked time cannot consume the recovered round")
                }
            }
        }
    }

    @Test
    fun returningFromAddPageStopsItsSearchAndReenteringStartsAgain() {
        for (style in InterfaceStyle.entries) {
            for (keyboardBack in listOf(false, true)) {
                withAddDeviceHarness(style, app = true) { harness ->
                    harness.openAddDevice()
                    if (keyboardBack) harness.sendKey(Key.Escape) else harness.clickContentDescription("返回")
                    harness.render()
                    assertTrue(harness.hasTab("耳机"))
                    assertFalse(harness.hasContentDescription("返回"))
                    assertEquals(ScanState.Idle, harness.state.value.scan)
                    assertEquals(1, harness.scanStops)
                    harness.openAddDevice()
                    assertTrue(harness.hasClickableText("停止搜索"))
                    assertEquals(2, harness.scanStarts)
                }
            }
        }
    }

    @Test
    fun cancelConnectionKeepsResultsOnAddPageAndAllowsAnotherAttempt() {
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style) { harness ->
                harness.openAddDevice()
                harness.clickText("连接")
                harness.render()
                assertEquals(ScanState.Idle, harness.state.value.scan)
                assertTrue(harness.hasClickableText("取消连接"))
                harness.clickText("取消连接")
                harness.render()
                assertEquals(ConnectionState.Disconnected, harness.state.value.connection)
                assertTrue(harness.hasText(requireNotNull(addDeviceCandidate.name)))
                assertTrue(harness.hasClickableText("重新搜索"))
                assertTrue(harness.hasClickableText("连接"))
                harness.clickText("连接")
                harness.render()
                assertTrue(harness.hasClickableText("取消连接"))
                assertIs<ConnectionState.Connecting>(harness.state.value.connection)
                assertEquals(2, harness.connectRequests.size)
            }
        }
    }

    @Test
    fun failedConnectionCanRetryInPlaceAndReadyExitPreservesThatSession() {
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style, app = true) { harness ->
                harness.openAddDevice()
                harness.clickText("连接")
                harness.render()
                val failed = assertIs<ConnectionState.Connecting>(harness.state.value.connection)
                harness.state.value = harness.state.value.copy(
                    connection = ConnectionState.Disconnected,
                    device = null,
                    lastError = ControlError(ControlErrorCode.FAILED, "耳机服务发现失败"),
                )
                harness.render()
                assertTrue(harness.hasContentDescription("返回"))
                assertTrue(harness.hasText("耳机服务发现失败"))
                assertTrue(harness.hasClickableText("重试"))
                harness.clickText("重试")
                harness.render()
                val retry = assertIs<ConnectionState.Connecting>(harness.state.value.connection)
                assertTrue(retry.sessionId > failed.sessionId)
                assertTrue(harness.hasContentDescription("返回"))
                assertTrue(harness.hasClickableText("取消连接"))
                assertFalse(harness.hasText("耳机服务发现失败"))
                harness.ready()
                harness.render()
                assertTrue(harness.hasTab("耳机"))
                assertTrue(harness.hasText("设备详情"))
                assertFalse(harness.hasContentDescription("返回"))
                val ready = assertIs<ConnectionState.Connected>(harness.state.value.connection)
                assertEquals(retry.sessionId, ready.sessionId)
                assertEquals(addDeviceCandidate.deviceId, ready.deviceId)
                assertEquals(0, harness.disconnects, "successful page cleanup must retain the ready connection")
            }
        }
    }

    @Test
    fun backCancelsOnlyTheUnfinishedConnectionStartedOnThisPage() {
        for (style in InterfaceStyle.entries) {
            for (keyboardBack in listOf(false, true)) {
                withAddDeviceHarness(style, app = true) { harness ->
                    harness.openAddDevice()
                    harness.clickText("连接")
                    harness.render()
                    if (keyboardBack) harness.sendKey(Key.Escape) else harness.clickContentDescription("返回")
                    harness.render()
                    assertTrue(harness.hasTab("耳机"))
                    assertEquals(ConnectionState.Disconnected, harness.state.value.connection)
                    assertEquals(1, harness.disconnects)
                    assertEquals(ScanState.Idle, harness.state.value.scan)
                }
            }
        }
    }

    @Test
    fun unrelatedReadyAndNewerSameDeviceSessionCannotCompleteOrBeCanceledByTheSelectedAttempt() {
        for (style in InterfaceStyle.entries) {
            for (sameDevice in listOf(false, true)) {
                withAddDeviceHarness(style, app = true) { harness ->
                    harness.openAddDevice()
                    harness.clickText("连接")
                    harness.render()
                    val selected = requireNotNull(harness.state.value.device)
                    val replacement = selected.copy(
                        deviceId = if (sameDevice) selected.deviceId else DeviceId("unrelated-headset"),
                        sessionId = selected.sessionId + 100,
                    )
                    val foreignReady = ConnectionState.Connected(replacement.deviceId, replacement.sessionId, replacement.profile)
                    harness.state.value = harness.state.value.copy(device = replacement, connection = foreignReady)
                    harness.render()
                    assertTrue(harness.hasContentDescription("返回"), "only the selected session may finish adding")
                    assertFalse(harness.hasTab("耳机"))
                    assertEquals(foreignReady, harness.state.value.connection)
                    assertEquals(0, harness.disconnects)
                    harness.clickContentDescription("返回")
                    harness.render()
                    assertTrue(harness.hasTab("耳机"))
                    assertTrue(harness.hasText("设备详情"))
                    assertEquals(foreignReady, harness.state.value.connection)
                    assertEquals(replacement, harness.state.value.device)
                    assertEquals(0, harness.disconnects, "leaving an old attempt must not disconnect a replacement session")
                }
            }
        }
    }
}

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddDeviceConnectionUiTest {
    @Test
    fun firstSupportedResultReplacesSearchGuidanceWithAnActionableList() {
        val other = addDeviceCandidate.copy(deviceId = DeviceId("other"), profile = null)
        val initial = disconnectedAndroidState().copy(candidates = persistentListOf(other))
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style, initial, app = true, width = 360, height = 540) { harness ->
                harness.openAddDevice()
                assertTrue(harness.hasText("打开耳机并靠近设备"))
                assertFalse(harness.hasText("附近设备"))

                harness.state.value = harness.state.value.copy(candidates = persistentListOf(other, addDeviceCandidate))
                harness.render()
                assertFalse(harness.hasText("打开耳机并靠近设备"))
                assertTrue(harness.hasText("已找到 1 副耳机"))
                assertTrue(harness.hasText("正在搜索附近的耳机…"))
                assertTrue(harness.clickableBounds("连接").height > 0f, "connect is visible without scrolling in $style")

                harness.clickText("停止搜索")
                harness.render()
                assertTrue(harness.hasText("已找到 1 副耳机"))
                assertTrue(harness.hasText("搜索已结束"))
                harness.clickText("连接")
                harness.render()
                assertTrue(harness.hasText("正在连接所选耳机"))
                assertTrue(harness.hasClickableText("取消连接"))
            }
        }
    }

    @Test
    fun multipleCandidatesShowMetadataAndBlockSiblingsWhileConnecting() {
        val second = addDeviceCandidate.copy(
            deviceId = DeviceId("AA:BB:CC:DD:EE:02"),
            rssi = -74,
        )
        val initial = disconnectedAndroidState().copy(candidates = persistentListOf(addDeviceCandidate, second))
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style, initial, app = true, width = 412, height = 840) { harness ->
                harness.openAddDevice()
                assertTrue(harness.hasText("已找到 2 副耳机"))
                assertTrue(harness.hasText("ZT · 信号强 · 地址末位 DD:EE:01"))
                assertTrue(harness.hasText("ZT · 信号中 · 地址末位 DD:EE:02"))

                harness.clickText("连接")
                harness.render()
                assertTrue(harness.hasClickableText("取消连接"))
                assertTrue(harness.hasText("正在连接其他耳机"))
                assertEquals(1, harness.connectRequests.size)
                harness.clickText("连接")
                harness.render()
                assertEquals(1, harness.connectRequests.size)

                harness.clickText("找不到耳机？")
                harness.render()
                assertTrue(harness.hasText("收起排查指引"))
                assertTrue(harness.hasText("设备识别信息"))
                assertTrue(harness.hasClickableText("查看诊断"))
            }
        }
    }
}

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
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
    fun connectingStaysOnAddPageUntilSelectedSessionIsReady() {
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style, app = true) { harness ->
                harness.openAddDevice()
                harness.clickText("连接")
                harness.render()
                assertTrue(harness.hasContentDescription("返回"), "connecting must remain on the add page in $style")
                assertTrue(harness.hasClickableText("取消连接"))
                assertFalse(harness.hasTab("耳机"))
                harness.ready()
                harness.render()
                assertTrue(harness.hasTab("耳机"))
                assertFalse(harness.hasContentDescription("返回"))
            }
        }
    }
}

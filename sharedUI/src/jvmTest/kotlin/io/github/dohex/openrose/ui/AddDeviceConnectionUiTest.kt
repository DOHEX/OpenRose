package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.InterfaceStyle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddDeviceConnectionUiTest {
    @Test
    fun connectingStaysOnAddPageUntilSelectedSessionIsReady() {
        for (style in InterfaceStyle.entries) {
            withAddDeviceHarness(style) { harness ->
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

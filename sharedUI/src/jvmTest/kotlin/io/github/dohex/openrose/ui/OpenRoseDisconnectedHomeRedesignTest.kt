@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRoseDisconnectedHomeRedesignTest {

    @Test
    fun disconnectedHomeShowsPairedDeviceCardWithDirectConnect() {
        for (style in InterfaceStyle.entries) {
            val pairedDevice = DeviceCandidate(
                deviceId = DeviceId("paired-i5-1"),
                name = "ROSE EarFree i5",
                rssi = null,
                profile = ModelProfile.I5_ZT,
                manufacturerDataHex = "",
            )
            withOpenRoseAppHarness(
                disconnectedAndroidState(UserSettings(interfaceStyle = style)).copy(bondedDevices = persistentListOf(pairedDevice)),
            ) { harness ->
                harness.render()
                // Paired devices section and device name
                assertTrue(harness.hasText("未连接 · 耳机开盖后将自动连接"), "Should display auto-connect hint in $style")
                assertTrue(harness.hasText("ROSE EarFree i5"), "Should display paired device name in $style")
                assertTrue(harness.hasText("ZT"), "Should display model configuration in $style")

                // Direct connect button
                assertTrue(harness.hasText("连接"), "Should display 连接 button for paired device in $style")

                // Clicking connect invokes onConnect
                harness.clickText("连接")
                assertEquals(listOf(DeviceId("paired-i5-1")), harness.connectRequests)

                // Secondary scan action changes to 扫描新设备 when paired devices exist
                assertTrue(harness.hasText("配对新耳机"), "Should provide 配对新耳机 entrance in $style")
            }
        }
    }

    @Test
    fun multiplePairedDevicesDisplayDistinctNamesAndProfiles() {
        val paired1 = DeviceCandidate(
            deviceId = DeviceId("paired-i5"),
            name = "My i5 Earbuds",
            rssi = null,
            profile = ModelProfile.I5_ZT,
            manufacturerDataHex = "",
        )
        val paired2 = DeviceCandidate(
            deviceId = DeviceId("paired-i7"),
            name = "My i7 Headset",
            rssi = null,
            profile = ModelProfile.I7_RS_V2,
            manufacturerDataHex = "",
        )
        withOpenRoseAppHarness(disconnectedAndroidState().copy(bondedDevices = persistentListOf(paired1, paired2))) { harness ->
            harness.render()
            assertTrue(harness.hasText("My i5 Earbuds"))
            assertTrue(harness.hasText("ZT"))
            assertTrue(harness.hasText("My i7 Headset"))
            assertTrue(harness.hasText("RS Common V2"))
        }
    }

    @Test
    fun primaryDashboardHidesSecondaryDisconnectButtonWhileDetailsRetainsIt() {
        withOpenRoseAppHarness(connectedI7State()) { harness ->
            harness.render()
            // On primary connected headphones dashboard:
            // "断开控制连接" is NOT present as an explicit button on the main dashboard content
            // (It is in the top-bar more menu, but not on the primary surface)
            assertFalse(harness.hasText("断开控制连接"), "Primary dashboard should not show secondary Disconnect button")

            // Navigate to 音频与设备
            harness.clickText("音频与设备")
            harness.render()
            assertFalse(harness.hasText("断开控制连接"), "Audio page should not show secondary Disconnect button")

            // Navigate to 设备详情
            harness.clickText("设备详情")
            harness.render()

            // In 设备详情: "断开控制连接" IS present in the ConnectionDetailsCard!
            assertTrue(harness.hasText("断开控制连接"), "Device Details must retain 断开控制连接 button in advanced section")

            // Clicking disconnect invokes onDisconnect
            harness.clickText("断开控制连接")
            assertEquals(1, harness.disconnectCount)
        }
    }
}

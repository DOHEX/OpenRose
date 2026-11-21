@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CirculationPanelUiTest {
    @Test
    fun aRemoteNoiseTapUsesTheHeldDeviceWhileItsPreviouslyReportedModeStaysVisible() = runOpenRoseUiTest(420, 600) {
        var selected: String? = null
        val reading = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree i5", modes = listOf("wind", "transparency"), mode = "wind")
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteHeadsetCard(RemoteHeadset("工作站", reading, false, null) { selected = it })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("通透")
        assertEquals("transparency", selected)
        assertTrue(panel.hasText("降噪模式 · 抗风噪"))
    }

    @Test
    fun phoneShowsTheSourceAndOnlyTheBatteryValuesReportedByThatSource() = runOpenRoseUiTest(420, 600) {
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                val reading = RemoteReading(RemoteControlStatus.READY, deviceName = "EarFree i5", left = 82, right = 71)
                RemoteHeadsetCard(RemoteHeadset("工作站", reading, false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("音源设备 · 工作站"))
        assertTrue(panel.hasText("EarFree i5"))
        assertTrue(panel.hasText("左耳 82% · 右耳 71% · 充电盒 未知"))
    }

    @Test
    fun hiddenUiStopsPeerRequestsAndResumesWithAFreshReading() = runOpenRoseUiTest(420, 600) {
        val visible = mutableStateOf(true)
        val requests = mutableListOf<String>()
        val client = CirculationClient { fields ->
            requests += fields.getValue("op")
            if (fields["op"] == "list") mapOf("paired.phone" to "手机", "near.phone" to "手机") else mapOf("status" to "idle")
        }
        setContent {
            CompositionLocalProvider(LocalCirculationVisible provides visible.value) {
                rememberRemoteHeadsets(client)
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertEquals(listOf("list", "read"), requests)
        visible.value = false
        panel.render()
        panel.advanceTimeBy(5_000)
        assertEquals(listOf("list", "read"), requests)
        visible.value = true
        panel.render()
        assertEquals(2, requests.count { it == "read" })
        assertEquals(2, requests.count { it == "list" })
    }

    @Test
    fun aConsumedOrExpiredPairingOfferDisappearsWhileThePageIsOpen() = runOpenRoseUiTest(420, 700) {
        var offer = emptyMap<String, String>()
        val client = CirculationClient { fields ->
            when (fields.getValue("op")) {
                "offer" -> mapOf("code" to "123456", "qr" to "QR", "validForMillis" to "3000").also { offer = it }
                else -> mapOf("offerState" to if (offer.isEmpty()) "idle" else "active") + offer
            }
        }
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                CirculationSettings(client, showQr = { AppText(it) })
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        panel.clickText("显示配对码")
        panel.render()
        assertTrue(panel.hasText("123456"))
        offer = emptyMap()
        panel.advanceTimeBy(2_050)
        assertFalse(panel.hasText("QR"))
        panel.clickText("刷新配对码")
        panel.render()
        assertTrue(panel.hasText("QR"))
        panel.advanceTimeBy(3_050)
        assertFalse(panel.hasText("123456"))
    }
}

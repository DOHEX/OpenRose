@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CirculationPanelUiTest {
    @Test
    fun aRemoteNoiseTapUsesTheHeldDeviceWhileItsPreviouslyReportedModeStaysVisible() = runOpenRoseUiTest(420, 600) {
        var selected: String? = null
        val reading = mapOf("deviceName" to "EarFree i5", "modes" to "wind,transparency", "mode" to "wind")
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
                RemoteHeadsetCard(RemoteHeadset("工作站", mapOf("deviceName" to "EarFree i5", "left" to "82", "right" to "71"), false, null) {})
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("音源设备 · 工作站"))
        assertTrue(panel.hasText("EarFree i5"))
        assertTrue(panel.hasText("左耳 82% · 右耳 71% · 充电盒 未知"))
    }
}

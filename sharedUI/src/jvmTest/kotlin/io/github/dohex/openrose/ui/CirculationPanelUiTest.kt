@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import kotlin.test.Test
import kotlin.test.assertTrue

class CirculationPanelUiTest {
    @Test
    fun phoneShowsTheSourceAndOnlyTheBatteryValuesReportedByThatSource() = runOpenRoseUiTest(420, 600) {
        setContent {
            ProvideInterfaceStyle(InterfaceStyle.MATERIAL3, dark = false) {
                RemoteHeadsetCard(
                    "工作站", mapOf("deviceName" to "EarFree i5", "left" to "82", "right" to "71"),
                )
            }
        }
        val panel = OpenRoseUiTestHarness(this)
        panel.render()
        assertTrue(panel.hasText("音源设备 · 工作站"))
        assertTrue(panel.hasText("EarFree i5"))
        assertTrue(panel.hasText("左耳 82% · 右耳 71% · 充电盒 未知"))
    }
}

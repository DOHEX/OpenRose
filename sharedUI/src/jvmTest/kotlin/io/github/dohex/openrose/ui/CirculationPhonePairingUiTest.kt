@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CirculationPhonePairingUiTest {
    @Test
    fun manualEntrySendsTheSixDigitsOnlyToTheSelectedComputer() {
        for (style in InterfaceStyle.entries) {
            runDesktopComposeUiTest(width = 390, height = 840) {
                mainClock.autoAdvance = false
                val scene = OpenRoseUiTestScene(this)
                val panel = OpenRoseUiTestHarness(scene)
                val commands = mutableListOf<Map<String, String>>()
                val client = CirculationClient { fields ->
                    if (fields["op"] == "list") {
                        mapOf("near.first" to "书房电脑", "near.second" to "笔记本")
                    } else {
                        commands += fields
                        mapOf("message" to "已配对")
                    }
                }
                scene.setContent {
                    ProvideInterfaceStyle(style, dark = false) {
                        CirculationSettings(client, scan = { {} })
                    }
                }
                panel.render()
                assertFalse(panel.hasText("确认配对"))
                panel.clickText("书房电脑")
                panel.render()
                onNode(hasSetTextAction()).performTextInput("98")
                panel.render()
                assertTrue(panel.hasDisabledButton("确认配对"))
                panel.clickText("笔记本")
                panel.render()
                onNode(hasSetTextAction()).performTextInput("001 234")
                panel.render()
                assertTrue(panel.hasTappableText("确认配对"))
                onNode(hasSetTextAction()).performImeAction()
                panel.render()
                assertEquals(listOf(mapOf("op" to "pair", "peer" to "second", "code" to "001234")), commands)
                assertTrue(panel.hasText("已配对"))
            }
        }
    }
}

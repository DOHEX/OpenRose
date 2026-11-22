package io.github.dohex.openrose.ui

import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MaterialDisconnectMenuUiTest {
    @Test
    fun pressedFeedbackFollowsTheMenuCorners() {
        runOpenRoseUiTest(420, 900) {
            val model = FakeOpenRoseModel(
                mutableStateOf(connectedI7State()),
                mutableStateOf(UiSettings(interfaceStyle = InterfaceStyle.MATERIAL3, appearance = Appearance.LIGHT)),
            )
            setContent { OpenRoseApp(model, BasicPlatformUi("Test")) }
            val harness = OpenRoseUiTestHarness(this)
            harness.render()
            val anchor = harness.clickableContentDescriptionBounds("耳机菜单").center
            touchInput {
                down(anchor)
                up()
            }
            harness.render()
            val item = harness.clickableBounds("断开控制连接")
            touchInput { down(item.center) }
            harness.render()
            val frame = harness.snapshot()
            val centerX = item.center.x.toInt()
            val rim = frame.getRGB(centerX, item.top.toInt() - 1)
            assertNotEquals(rim, frame.getRGB(centerX, item.top.toInt() + 2), "the pressed feedback is visible")
            for (x in listOf(item.left.toInt() + 2, item.right.toInt() - 3)) {
                for (y in listOf(item.top.toInt() + 2, item.bottom.toInt() - 3)) {
                    assertEquals(rim, frame.getRGB(x, y), "the menu surface should show through each rounded corner")
                }
            }
            assertEquals(0, model.disconnectCount)
            touchInput { up() }
            harness.render()
            assertEquals(1, model.disconnectCount)
        }
    }
}

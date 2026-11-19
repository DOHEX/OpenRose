package io.github.dohex.openrose.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiuixOverscrollUiTest {

    @Test
    fun headphonesPageShorterThanViewportStillBouncesUnderDrag() = withOpenRoseAppHarness(
        disconnectedAndroidState(),
        initialUiSettings = UiSettings(interfaceStyle = InterfaceStyle.MIUIX),
    ) { harness ->
        harness.render()
        val title = "添加你的 ROSE 耳机"
        val restingTop = harness.textBounds(title).top

        harness.pressAndDrag(start = Offset(210f, 300f), delta = Offset(0f, 240f))
        harness.render(1)
        val draggedTop = harness.textBounds(title).top
        assertTrue(draggedTop > restingTop + 20f, "Content should follow the drag: resting $restingTop, dragged $draggedTop")

        harness.releaseTouch()
        harness.render(60)
        assertEquals(restingTop, harness.textBounds(title).top, "Content should spring back after release")
    }
}

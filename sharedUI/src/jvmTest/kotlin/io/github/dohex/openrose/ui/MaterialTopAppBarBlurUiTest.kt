@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

class MaterialTopAppBarBlurUiTest {

    /**
     * A half-collapsed blurred bar must not tint darker than the page surface it sits on. The headless
     * renderer cannot run the blur shader, so the bar carries only the translucent surface tint that
     * [appBarBackdropBlur] lays under it.
     */
    @Test
    fun blurredLargeTopAppBarKeepsSurfaceToneWhileCollapsing() = runOpenRoseUiTest(420, 400) {
        val barState = TopAppBarState(-Float.MAX_VALUE, 0f, 0f)
        setContent {
            TestPageContent(InterfaceStyle.MATERIAL3) {
                Column {
                    LargeTopAppBar(
                        title = { Text("Title") },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState),
                        colors = materialTopAppBarColors(backdropActive = true),
                    )
                }
            }
        }
        renderFor(100)
        runOnUiThread { barState.heightOffset = barState.heightOffsetLimit / 2 }
        renderFor(100)

        val image = capture()
        val page = luminance(image.getRGB(10, image.height - 10))
        val bar = luminance(image.getRGB(10, 10))
        assertTrue(bar >= page - 0.03, "Half-collapsed bar luminance $bar must match page surface $page")
    }

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }
}

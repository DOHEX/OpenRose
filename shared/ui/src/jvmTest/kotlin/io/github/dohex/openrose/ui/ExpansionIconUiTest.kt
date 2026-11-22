package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class ExpansionIconUiTest {
    @Test
    fun expansionDirectionStaysVerticalInRightToLeftLayouts() {
        for (style in InterfaceStyle.entries) {
            runOpenRoseUiTest(32, 32) {
                val expanded = mutableStateOf(false)
                val direction = mutableStateOf(LayoutDirection.Ltr)
                setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                        ProvideInterfaceStyle(style, dark = false) {
                            ExpansionIcon(expanded.value, Modifier.size(32.dp), tint = Color.Black)
                        }
                    }
                }
                fun pixels(open: Boolean, layout: LayoutDirection): IntArray {
                    expanded.value = open
                    direction.value = layout
                    renderFor(32)
                    return capture().getRGB(0, 0, 32, 32, null, 0, 32)
                }

                val down = pixels(false, LayoutDirection.Ltr)
                val up = pixels(true, LayoutDirection.Ltr)
                assertFalse(down.contentEquals(up), "expanding changes the arrow in $style")
                assertContentEquals(down, pixels(false, LayoutDirection.Rtl), "collapsed arrow in $style")
                assertContentEquals(up, pixels(true, LayoutDirection.Rtl), "expanded arrow in $style")
            }
        }
    }
}

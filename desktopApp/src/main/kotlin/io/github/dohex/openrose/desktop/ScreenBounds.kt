package io.github.dohex.openrose.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment

internal fun usableScreenSize(): DpSize? = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching null
    val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    DpSize(bounds.width.dp, bounds.height.dp)
}.getOrNull()

internal fun DpSize.fitTo(screen: DpSize?): DpSize = if (screen == null) {
    this
} else {
    DpSize(width.coerceAtMost(screen.width), height.coerceAtMost(screen.height))
}

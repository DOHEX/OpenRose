package io.github.dohex.openrose.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment

/**
 * The area of the default screen that windows can use, without taskbars and panels; null where Java reports
 * none (no X display, for example a Wayland session without XWayland). Best effort: the figure is in the
 * units Java reports, which match dp where the platform passes its scale on to Java.
 */
internal fun usableScreenSize(): DpSize? = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching null
    val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    DpSize(bounds.width.dp, bounds.height.dp)
}.getOrNull()

/** This size, kept within [screen] when one is known. */
internal fun DpSize.fitTo(screen: DpSize?): DpSize = if (screen == null) {
    this
} else {
    DpSize(width.coerceAtMost(screen.width), height.coerceAtMost(screen.height))
}

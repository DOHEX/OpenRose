package io.github.dohex.openrose.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

fun OpenRoseUiTestHarness.hasText(expected: String): Boolean = nodes().any { it.hasText(expected) }

/** True when some text holds [expected] as one whole line, such as one line of a multi-line row summary. */
fun OpenRoseUiTestHarness.hasTextLine(expected: String): Boolean = nodes().any { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { expected in it.text.lines() }
}

fun OpenRoseUiTestHarness.countText(expected: String): Int = nodes().count { it.hasText(expected) }

fun OpenRoseUiTestHarness.clickableBounds(expected: String): Rect = textNodes(expected).firstNotNullOfOrNull { node ->
    node.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
}?.boundsInRoot ?: error("No clickable node found for '$expected'")

fun OpenRoseUiTestHarness.textBounds(expected: String): Rect = textNodes(expected).firstOrNull()?.boundsInRoot
    ?: error("No text node found for '$expected'")

/** Bounds of every node showing [expected], main window first, then popups and dialogs. */
fun OpenRoseUiTestHarness.allTextBounds(expected: String): List<Rect> = textNodes(expected).map { it.boundsInRoot }

internal fun OpenRoseUiTestHarness.textNodes(expected: String): List<SemanticsNode> = nodes().filter { it.hasText(expected) }

internal fun SemanticsNode.hasText(expected: String) = config.getOrNull(SemanticsProperties.Text).orEmpty().any {
    it.text == expected
}

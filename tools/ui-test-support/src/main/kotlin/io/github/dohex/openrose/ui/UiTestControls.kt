package io.github.dohex.openrose.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

fun OpenRoseUiTestHarness.hasRole(expected: Role): Boolean = nodes().any { node ->
    node.config.getOrNull(SemanticsProperties.Role) == expected
}

fun OpenRoseUiTestHarness.hasContentDescription(expected: String): Boolean = nodes().any { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
}

fun OpenRoseUiTestHarness.clickableContentDescriptionBounds(expected: String): Rect = nodes().firstOrNull {
    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
}?.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }?.boundsInRoot
    ?: error("No clickable node found for content description '$expected'")

fun OpenRoseUiTestHarness.hasClickableText(expected: String): Boolean = textNodes(expected).any { node ->
    node.hasAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
}

fun OpenRoseUiTestHarness.hasDisabledText(expected: String): Boolean = textNodes(expected).any { node ->
    node.hasAncestorOrSelf { it.config.contains(SemanticsProperties.Disabled) }
}

fun OpenRoseUiTestHarness.hasDisabledButton(expected: String): Boolean = hasDisabledText(expected)

/** As with a real touch, a disabled control takes no taps: Material marks it disabled, Miuix drops its click action. */
fun OpenRoseUiTestHarness.hasTappableText(expected: String): Boolean = hasClickableText(expected) && !hasDisabledText(expected)

fun OpenRoseUiTestHarness.hasRadioText(expected: String): Boolean = textNodes(expected).any { node ->
    node.hasAncestorOrSelf { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
}

fun OpenRoseUiTestHarness.hasSelectedText(expected: String): Boolean = textNodes(expected).any { node ->
    node.hasAncestorOrSelf {
        it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
            it.config.getOrNull(SemanticsProperties.Selected) == true
    }
}

fun OpenRoseUiTestHarness.clickText(text: String, expectedRole: Role? = null) {
    val clickNode = textNodes(text).firstNotNullOfOrNull { node ->
        node.findAncestorOrSelf { candidate ->
            candidate.config.getOrNull(SemanticsActions.OnClick) != null &&
                (expectedRole == null || candidate.config.getOrNull(SemanticsProperties.Role) == expectedRole)
        }
    } ?: error("No clickable node found for '$text'")
    invokeClick(clickNode, text)
}

fun OpenRoseUiTestHarness.clickContentDescription(expected: String) {
    val node = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
    } ?: error("No node found with content description '$expected'")
    val clickNode = node.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
        ?: error("No clickable node found for content description '$expected'")
    invokeClick(clickNode, expected)
}

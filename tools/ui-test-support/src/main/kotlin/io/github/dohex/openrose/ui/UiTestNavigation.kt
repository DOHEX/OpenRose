package io.github.dohex.openrose.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

fun OpenRoseUiTestHarness.hasTab(expected: String): Boolean = nodes().any { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        node.hasTabLabel(expected)
}

fun OpenRoseUiTestHarness.hasSelectedTab(expected: String): Boolean = nodes().any { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        node.hasTabLabel(expected) &&
        node.config.getOrNull(SemanticsProperties.Selected) == true
}

fun OpenRoseUiTestHarness.clickTab(text: String) {
    val clickNode = nodes().firstOrNull { node ->
        val hasMatchingText = node.hasText(text)
        val hasMatchingDesc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(text)
        (hasMatchingText || hasMatchingDesc) && node.hasAncestorOrSelf { candidate ->
            candidate.config.getOrNull(SemanticsActions.OnClick) != null &&
                candidate.config.getOrNull(SemanticsProperties.Role) == Role.Tab
        }
    }?.findAncestorOrSelf {
        it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsProperties.Role) == Role.Tab
    } ?: error("No clickable tab found for '$text'")
    invokeClick(clickNode, text)
}

/** From the Settings page, opens the Personalization page that holds interface preferences. */
fun OpenRoseUiTestHarness.openPersonalization() {
    clickText("个性化")
    render()
}

fun OpenRoseUiTestHarness.focus(label: String) {
    repeat(100) {
        if (focusedLabel().contains(label)) return
        sendKey(Key.Tab)
        render(frames = 5)
    }
    error("Could not focus '$label': ${focusedLabel()}")
}

private fun OpenRoseUiTestHarness.focusedLabel(): String = nodes().lastOrNull {
    it.config.getOrNull(SemanticsProperties.Focused) == true
}?.let { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } + " " +
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
}.orEmpty()

private fun SemanticsNode.hasTabLabel(label: String): Boolean = hasText(label) || children.any {
    it.hasText(label) || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
}

package io.github.dohex.openrose.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

private fun OpenRoseUiTestHarness.taggedNodes(tag: String) = nodes().filter {
    it.config.getOrNull(SemanticsProperties.TestTag) == tag
}

private fun SemanticsNode.subtree(): List<SemanticsNode> = listOf(this) + children.flatMap { it.subtree() }

/** Whether production exposed a node under [tag]; tags carry state names, never wording. */
fun OpenRoseUiTestHarness.hasTag(tag: String): Boolean = taggedNodes(tag).isNotEmpty()

fun OpenRoseUiTestHarness.tagBounds(tag: String): Rect {
    val node = taggedNodes(tag).firstOrNull() ?: error("No node found for tag '$tag'")
    return node.boundsInRoot
}

fun OpenRoseUiTestHarness.hasTagWithPrefix(prefix: String): Boolean = nodes().any {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

/** The radio option publishes Selected on its tagged node; a component may also publish it on a child. */
fun OpenRoseUiTestHarness.isTagSelected(tag: String): Boolean = taggedNodes(tag).any { node ->
    node.subtree().any { it.config.getOrNull(SemanticsProperties.Selected) == true }
}

fun OpenRoseUiTestHarness.isTagDisabled(tag: String): Boolean = taggedNodes(tag).any { node ->
    node.subtree().any { it.config.contains(SemanticsProperties.Disabled) } ||
        node.hasAncestorOrSelf { it.config.contains(SemanticsProperties.Disabled) }
}

private fun OpenRoseUiTestHarness.clickNodeFor(tag: String): SemanticsNode? = taggedNodes(tag).firstNotNullOfOrNull { node ->
    node.subtree().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
        ?: node.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
}

/** A disabled control is not tappable, as with a real touch: the semantic action alone must not bypass it. */
fun OpenRoseUiTestHarness.isTagClickable(tag: String): Boolean = clickNodeFor(tag) != null && !isTagDisabled(tag)

fun OpenRoseUiTestHarness.clickTag(tag: String) {
    check(!isTagDisabled(tag)) { "Tag '$tag' is disabled and takes no taps" }
    invokeClick(clickNodeFor(tag) ?: error("No clickable node found for tag '$tag'"), tag)
}

/** Text rendered under [tag]; callers compare it with text their own fixture supplied. */
fun OpenRoseUiTestHarness.tagTexts(tag: String): List<String> = taggedNodes(tag).flatMap { node ->
    node.subtree().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
}

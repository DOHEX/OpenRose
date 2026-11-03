@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Common ImageComposeScene setup for public OpenRoseApp interaction tests. */
internal suspend fun createOpenRoseUiTestScene(
    width: Int,
    height: Int,
    content: @Composable () -> Unit,
): ImageComposeScene = ImageComposeScene(
    width = width,
    height = height,
    density = Density(1f),
    coroutineContext = currentCoroutineContext(),
) {
    content()
}

/** Shared rendering and semantics operations; each test keeps its own state and callbacks. */
internal open class OpenRoseUiTestHarness(protected val scene: ImageComposeScene) {
    private var nextFrameTimeNanos = 0L

    suspend fun render(frames: Int = 10) {
        repeat(frames) {
            delay(35)
            scene.render(nextFrameTimeNanos).close()
            nextFrameTimeNanos += 35_000_000L
        }
    }

    fun close() = scene.close()

    fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            result += node
            node.children.forEach(::collect)
        }
        scene.semanticsOwners.forEach { collect(it.rootSemanticsNode) }
        return result
    }

    fun hasText(expected: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
    }

    fun countText(expected: String): Int = nodes().count { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
    }

    fun hasContentDescription(expected: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
    }

    fun hasClickableText(expected: String): Boolean = textNodes(expected).any { node ->
        node.hasAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
    }

    fun clickableBounds(expected: String): Rect = textNodes(expected).firstNotNullOfOrNull { node ->
        node.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
    }?.boundsInRoot ?: error("No clickable node found for '$expected'")

    fun textBounds(expected: String): Rect = textNodes(expected).firstOrNull()?.boundsInRoot
        ?: error("No text node found for '$expected'")

    fun snapshot(): BufferedImage {
        val image = scene.render(nextFrameTimeNanos)
        val data = requireNotNull(image.encodeToData())
        return try {
            ImageIO.read(ByteArrayInputStream(data.bytes))
        } finally {
            data.close()
            image.close()
        }
    }

    fun clickableContentDescriptionBounds(expected: String): Rect = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
    }?.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }?.boundsInRoot
        ?: error("No clickable node found for content description '$expected'")

    fun hasDisabledText(expected: String): Boolean = textNodes(expected).any { node ->
        node.hasAncestorOrSelf { it.config.contains(SemanticsProperties.Disabled) }
    }

    fun hasDisabledButton(expected: String): Boolean = hasDisabledText(expected)

    fun hasSelectedText(expected: String): Boolean = textNodes(expected).any { node ->
        node.hasAncestorOrSelf {
            it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                it.config.getOrNull(SemanticsProperties.Selected) == true
        }
    }

    fun hasTab(expected: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            (hasText(node, expected) || node.children.any { hasText(it, expected) || it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected) })
    }

    fun hasSelectedTab(expected: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            (hasText(node, expected) || node.children.any { hasText(it, expected) || it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected) }) &&
            node.config.getOrNull(SemanticsProperties.Selected) == true
    }

    fun clickTab(text: String) {
        val clickNode = nodes().firstOrNull { node ->
            val hasMatchingText = hasText(node, text)
            val hasMatchingDesc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(text)
            (hasMatchingText || hasMatchingDesc) && node.hasAncestorOrSelf { candidate ->
                candidate.config.getOrNull(SemanticsActions.OnClick) != null &&
                    candidate.config.getOrNull(SemanticsProperties.Role) == Role.Tab
            }
        }?.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            ?: nodes().firstOrNull { candidate ->
                candidate.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    candidate.config.getOrNull(SemanticsActions.OnClick) != null &&
                    (hasText(candidate, text) || candidate.children.any { hasText(it, text) || it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(text) })
            } ?: error("No clickable tab found for '$text'")
        invokeClick(clickNode, text)
    }

    fun clickText(text: String, expectedRole: Role? = null) {
        val clickNode = textNodes(text).firstNotNullOfOrNull { node ->
            node.findAncestorOrSelf { candidate ->
                candidate.config.getOrNull(SemanticsActions.OnClick) != null &&
                    (expectedRole == null || candidate.config.getOrNull(SemanticsProperties.Role) == expectedRole)
            }
        } ?: error("No clickable node found for '$text'")
        invokeClick(clickNode, text)
    }

    fun clickContentDescription(expected: String) {
        val node = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
        } ?: error("No node found with content description '$expected'")
        val clickNode = node.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
            ?: error("No clickable node found for content description '$expected'")
        invokeClick(clickNode, expected)
    }

    fun sendKey(key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isShiftPressed = shift))
    }

    suspend fun focus(label: String) {
        repeat(100) {
            if (focusedLabel().contains(label)) return
            sendKey(Key.Tab)
            render(frames = 5)
        }
        error("Could not focus '$label': ${focusedLabel()}")
    }

    private fun focusedLabel(): String = nodes().lastOrNull {
        it.config.getOrNull(SemanticsProperties.Focused) == true
    }?.let { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } + " " +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    }.orEmpty()

    private fun taggedNodes(tag: String): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun SemanticsNode.subtree(): List<SemanticsNode> = listOf(this) + children.flatMap { it.subtree() }

    /** Whether production exposed a node under [tag]; tags carry state names, never wording. */
    fun hasTag(tag: String): Boolean = taggedNodes(tag).isNotEmpty()

    fun hasTagWithPrefix(prefix: String): Boolean = nodes().any {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /** The radio option publishes Selected on its tagged node; a component may also publish it on a child. */
    fun isTagSelected(tag: String): Boolean = taggedNodes(tag).any { node ->
        node.subtree().any { it.config.getOrNull(SemanticsProperties.Selected) == true }
    }

    fun isTagDisabled(tag: String): Boolean = taggedNodes(tag).any { node ->
        node.subtree().any { it.config.contains(SemanticsProperties.Disabled) } ||
            node.hasAncestorOrSelf { it.config.contains(SemanticsProperties.Disabled) }
    }

    private fun clickNodeFor(tag: String): SemanticsNode? = taggedNodes(tag).firstNotNullOfOrNull { node ->
        node.subtree().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
            ?: node.findAncestorOrSelf { it.config.getOrNull(SemanticsActions.OnClick) != null }
    }

    /** A disabled control is not tappable, as with a real touch: the semantic action alone must not bypass it. */
    fun isTagClickable(tag: String): Boolean = clickNodeFor(tag) != null && !isTagDisabled(tag)

    fun clickTag(tag: String) {
        check(!isTagDisabled(tag)) { "Tag '$tag' is disabled and takes no taps" }
        invokeClick(clickNodeFor(tag) ?: error("No clickable node found for tag '$tag'"), tag)
    }

    /** Text rendered under [tag]; callers compare it with text their own fixture supplied. */
    fun tagTexts(tag: String): List<String> = taggedNodes(tag).flatMap { node ->
        node.subtree().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
    }

    private fun textNodes(expected: String): List<SemanticsNode> = nodes().filter { hasText(it, expected) }

    private fun hasText(node: SemanticsNode, expected: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }

    private fun invokeClick(node: SemanticsNode, label: String) {
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("Click action disappeared for '$label'")
        check(action()) { "Click action for '$label' returned false" }
    }
}

private inline fun SemanticsNode.findAncestorOrSelf(predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
    var current: SemanticsNode? = this
    while (current != null) {
        if (predicate(current)) return current
        current = current.parent
    }
    return null
}

private inline fun SemanticsNode.hasAncestorOrSelf(predicate: (SemanticsNode) -> Boolean): Boolean =
    findAncestorOrSelf(predicate) != null

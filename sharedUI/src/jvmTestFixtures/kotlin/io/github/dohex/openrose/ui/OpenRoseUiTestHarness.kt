@file:OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.*
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import java.awt.image.BufferedImage

/** Virtual time one frame of [OpenRoseUiTestHarness.render] advances. */
private const val FRAME_MILLIS = 35L

/**
 * Runs [block] against a fresh [width]×[height] scene at density 1. The scene clock is virtual and
 * moves through explicit frame advancement and the frames needed by scroll actions.
 */
internal fun runOpenRoseUiTest(width: Int, height: Int, block: suspend OpenRoseUiTestScene.() -> Unit) = runDesktopComposeUiTest(width = width, height = height) {
    mainClock.autoAdvance = false
    OpenRoseUiTestScene(this).block()
}

/** One official Compose UI test scene; harnesses drive it only through its virtual clock. */
internal class OpenRoseUiTestScene(private val test: DesktopComposeUiTest) {
    private var drawing by mutableStateOf(true)

    val currentTime: Long get() = test.mainClock.currentTime

    /** Shows [content] at the scene's full size; the wrapper adds no layout or semantics of its own. */
    fun setContent(content: @Composable () -> Unit) = test.setContent {
        Box(Modifier.drawWithContent { if (drawing) drawContent() }, propagateMinConstraints = true) {
            content()
        }
    }

    /** Advances the clock, drawing every frame on the way. */
    fun renderFor(milliseconds: Long) {
        test.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        test.waitForIdle()
    }

    /**
     * Lets [milliseconds] pass: frames still compose, lay out and run their effects, but skip drawing,
     * which a long wait would otherwise spend rasterizing frames no assertion looks at.
     */
    fun passTime(milliseconds: Long) {
        drawing = false
        try {
            test.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        } finally {
            drawing = true
        }
    }

    fun resize(width: Int, height: Int) {
        test.runOnUiThread { test.scene.size = IntSize(width, height) }
    }

    fun rootNodes(): List<SemanticsNode> = test.runWithoutImplicitWait { test.onAllNodes(isRoot()).fetchSemanticsNodes() }

    fun capture(): BufferedImage = test.captureToImage().toAwtImage()

    fun scrollToText(text: String) {
        // Compose's scroll action needs clock frames to complete its animation.
        val autoAdvance = test.mainClock.autoAdvance
        test.mainClock.autoAdvance = true
        try {
            test.onNodeWithText(text).performScrollTo()
        } finally {
            test.mainClock.autoAdvance = autoAdvance
        }
    }

    fun sendKeyEvent(event: KeyEvent) {
        test.runOnUiThread { test.scene.sendKeyEvent(event) }
    }

    fun <T> runOnUiThread(action: () -> T): T = test.runOnUiThread(action)
}

private val androidPlatform = PlatformState(
    permission = PermissionState.GRANTED,
    transport = TransportAvailability.Available,
    hostPlatform = HostPlatform.ANDROID,
    controlTransportApplicability = PlatformApplicability.APPLICABLE,
)

/** Android host with no control connection. */
internal fun disconnectedAndroidState(): ControlState = ControlState(platform = androidPlatform)

/** Android host with a ready I7 control connection and a fully readable device. */
internal fun connectedI7State(): ControlState {
    val profile = ModelProfile.I7_RS_V2
    val deviceId = DeviceId("test-i7")
    return ControlState(
        platform = androidPlatform,
        connection = ConnectionState.Connected(deviceId, 1L, profile, ConnectionPhase.READY),
        device = DeviceState(
            deviceId = deviceId,
            sessionId = 1L,
            profile = profile,
            capabilities = DeviceCapabilities(
                readableFields = DeviceField.entries.toPersistentSet(),
                writableSettings = DeviceSetting.entries.toPersistentSet(),
                noiseControl = profile.capabilities().noiseControl,
                equalizer = profile.capabilities().equalizer,
                touchControl = profile.capabilities().touchControl,
                audioControl = profile.capabilities().audioControl,
                promptVolumeLevels = profile.capabilities().promptVolumeLevels,
                gainLevels = persistentSetOf(0, 1, 2),
                hearingProtectionLevels = (0..6).toPersistentSet(),
            ),
            fields = persistentMapOf(
                DeviceField.LEFT_BATTERY to FieldReading.Known(FieldValue.Number(90), "90"),
                DeviceField.RIGHT_BATTERY to FieldReading.Known(FieldValue.Number(90), "90"),
                DeviceField.CASE_BATTERY to FieldReading.Known(FieldValue.Number(80), "80"),
                DeviceField.NOISE_MODE to FieldReading.Known(FieldValue.Text("anc"), "anc"),
            ),
        ),
    )
}

/** Shared rendering and semantics operations; each test keeps its own state and callbacks. */
internal open class OpenRoseUiTestHarness(protected val scene: OpenRoseUiTestScene) {
    /** Current virtual time of the scene clock, in milliseconds. */
    val currentTime: Long get() = scene.currentTime

    /** Renders [frames] frames of virtual time, then settles composition and layout. */
    fun render(frames: Int = 10) = scene.renderFor(frames * FRAME_MILLIS)

    /** Lets exactly [milliseconds] of virtual time pass, running the frames and effects that fall due. */
    fun advanceTimeBy(milliseconds: Long) = scene.passTime(milliseconds)

    /** Lays the scene out at a new size, as a resized window would. */
    fun resize(width: Int, height: Int) = scene.resize(width, height)

    fun scrollToText(text: String) = scene.scrollToText(text)

    /** Every merged semantics node, main window first, then popups and dialogs; reading never settles the scene. */
    fun nodes(): List<SemanticsNode> {
        val roots = scene.rootNodes()
        return scene.runOnUiThread {
            val result = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                result += node
                node.children.forEach(::collect)
            }
            roots.forEach(::collect)
            result
        }
    }

    fun hasText(expected: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
    }

    /** True when some text holds [expected] as one whole line, such as one line of a multi-line row summary. */
    fun hasTextLine(expected: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { expected in it.text.lines() }
    }

    fun countText(expected: String): Int = nodes().count { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }
    }

    fun hasRole(expected: Role): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Role) == expected
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

    /** The current frame as an image; capturing draws pending changes without advancing the clock. */
    fun snapshot(): BufferedImage = scene.capture()

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

    /** From the Settings page, opens the Personalization page that holds interface preferences. */
    fun openPersonalization() {
        clickText("个性化")
        render()
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

    /** Moves the slider described by [expected] as an accessibility service would; false when it refuses. */
    fun setProgress(expected: String, value: Float): Boolean {
        val node = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(expected)
        } ?: error("No node found with content description '$expected'")
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No progress action for '$expected'")
        return scene.runOnUiThread { action(value) }
    }

    fun sendKey(key: Key, shift: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift))
        scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isShiftPressed = shift))
    }

    fun focus(label: String) {
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

    private fun taggedNodes(tag: String): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

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

    private fun hasText(node: SemanticsNode, expected: String): Boolean = node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == expected }

    private fun invokeClick(node: SemanticsNode, label: String) {
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("Click action disappeared for '$label'")
        check(scene.runOnUiThread { action() }) { "Click action for '$label' returned false" }
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

private inline fun SemanticsNode.hasAncestorOrSelf(predicate: (SemanticsNode) -> Boolean): Boolean = findAncestorOrSelf(predicate) != null

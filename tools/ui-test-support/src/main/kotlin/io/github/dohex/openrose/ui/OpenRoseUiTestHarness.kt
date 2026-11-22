@file:OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
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
fun runOpenRoseUiTest(
    width: Int,
    height: Int,
    block: suspend OpenRoseUiTestScene.() -> Unit,
) = runDesktopComposeUiTest(width = width, height = height) {
    mainClock.autoAdvance = false
    OpenRoseUiTestScene(this).block()
}

/** One official Compose UI test scene; harnesses drive it only through its virtual clock. */
class OpenRoseUiTestScene(private val test: DesktopComposeUiTest) {
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

    /** Injects touch at main-window coordinates, including when a popup is covering the content. */
    fun touchInput(block: TouchInjectionScope.() -> Unit) {
        test.onAllNodes(isRoot())[0].performTouchInput(block)
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
fun disconnectedAndroidState(): ControlState = ControlState(platform = androidPlatform)

/** Android host with a ready I7 control connection and a fully readable device. */
fun connectedI7State(): ControlState {
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
open class OpenRoseUiTestHarness(protected val scene: OpenRoseUiTestScene) {
    /** Current virtual time of the scene clock, in milliseconds. */
    val currentTime: Long get() = scene.currentTime

    /** Renders [frames] frames of virtual time, then settles composition and layout. */
    fun render(frames: Int = 10) = scene.renderFor(frames * FRAME_MILLIS)

    /** Lets exactly [milliseconds] of virtual time pass, running the frames and effects that fall due. */
    fun advanceTimeBy(milliseconds: Long) = scene.passTime(milliseconds)

    /** Lays the scene out at a new size, as a resized window would. */
    fun resize(width: Int, height: Int) = scene.resize(width, height)

    fun scrollToText(text: String) = scene.scrollToText(text)

    /** Drags over ten moves, leaving the finger down until [releaseTouch]. */
    fun pressAndDrag(start: Offset, delta: Offset) = scene.touchInput {
        down(start)
        repeat(10) { moveBy(delta / 10f) }
    }

    fun releaseTouch() = scene.touchInput { up() }

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

    /** The current frame as an image; capturing draws pending changes without advancing the clock. */
    fun snapshot(): BufferedImage = scene.capture()

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

    internal fun invokeClick(node: SemanticsNode, label: String) {
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("Click action disappeared for '$label'")
        check(scene.runOnUiThread { action() }) { "Click action for '$label' returned false" }
    }
}

internal inline fun SemanticsNode.findAncestorOrSelf(predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
    var current: SemanticsNode? = this
    while (current != null) {
        if (predicate(current)) return current
        current = current.parent
    }
    return null
}

internal inline fun SemanticsNode.hasAncestorOrSelf(predicate: (SemanticsNode) -> Boolean): Boolean = findAncestorOrSelf(predicate) != null

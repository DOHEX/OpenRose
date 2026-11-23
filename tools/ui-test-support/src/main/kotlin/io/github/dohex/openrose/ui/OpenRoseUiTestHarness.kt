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

private const val FRAME_MILLIS = 35L

fun runOpenRoseUiTest(
    width: Int,
    height: Int,
    block: suspend OpenRoseUiTestScene.() -> Unit,
) = runDesktopComposeUiTest(width = width, height = height) {
    mainClock.autoAdvance = false
    OpenRoseUiTestScene(this).block()
}

class OpenRoseUiTestScene(private val test: DesktopComposeUiTest) {
    private var drawing by mutableStateOf(true)

    val currentTime: Long get() = test.mainClock.currentTime

    fun setContent(content: @Composable () -> Unit) = test.setContent {
        Box(Modifier.drawWithContent { if (drawing) drawContent() }, propagateMinConstraints = true) {
            content()
        }
    }

    fun renderFor(milliseconds: Long) {
        test.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        test.waitForIdle()
    }

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
        val autoAdvance = test.mainClock.autoAdvance
        test.mainClock.autoAdvance = true
        try {
            test.onNodeWithText(text).performScrollTo()
        } finally {
            test.mainClock.autoAdvance = autoAdvance
        }
    }

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

fun disconnectedAndroidState(): ControlState = ControlState(platform = androidPlatform)

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
                promptVolumeLevels = profile.capabilities(FieldValue.Firmware(listOf(3, 0, 4), "304")).promptVolumeLevels,
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

open class OpenRoseUiTestHarness(protected val scene: OpenRoseUiTestScene) {
    val currentTime: Long get() = scene.currentTime

    fun render(frames: Int = 10) = scene.renderFor(frames * FRAME_MILLIS)

    fun advanceTimeBy(milliseconds: Long) = scene.passTime(milliseconds)

    fun resize(width: Int, height: Int) = scene.resize(width, height)

    fun scrollToText(text: String) = scene.scrollToText(text)

    fun pressAndDrag(start: Offset, delta: Offset) = scene.touchInput {
        down(start)
        repeat(10) { moveBy(delta / 10f) }
    }

    fun releaseTouch() = scene.touchInput { up() }

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

    fun snapshot(): BufferedImage = scene.capture()

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

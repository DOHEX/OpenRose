package io.github.dohex.openrose.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun Modifier.bandDrag(
    enabled: Boolean,
    minGain: Int,
    maxGain: Int,
    inset: Dp,
    onDrag: (Int) -> Unit,
    onRelease: (lifted: Boolean) -> Unit,
): Modifier = pointerInput(enabled, minGain, maxGain, inset) {
    if (!enabled) return@pointerInput
    val insetPx = inset.toPx()
    val gainAt = { y: Float -> gainAtHeight(y, size.height, insetPx, minGain, maxGain) }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var lifted = false
        try {
            lifted = trackBandGesture(down, gainAt, onDrag)
        } finally {
            onRelease(lifted)
        }
    }
}

private fun gainAtHeight(y: Float, height: Int, inset: Float, minGain: Int, maxGain: Int): Int {
    val fraction = ((y - inset) / (height - 2 * inset)).coerceIn(0f, 1f)
    return (maxGain - fraction * (maxGain - minGain)).roundToInt()
}

private suspend fun AwaitPointerEventScope.trackBandGesture(
    down: PointerInputChange,
    gainAt: (Float) -> Int,
    onDrag: (Int) -> Unit,
): Boolean {
    onDrag(gainAt(down.position.y))
    val gesture = BandGesture(viewConfiguration.touchSlop, down.position)
    var lifted = false
    var handedOver = false
    while (!lifted && !handedOver) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
        if (change == null || change.isConsumed) {
            handedOver = true
        } else {
            lifted = !change.pressed
            handedOver = gesture.handsOver(change.position, lifted)
            if (!handedOver && (gesture.claimed || lifted)) change.consume()
            if (!handedOver && !lifted) onDrag(gainAt(change.position.y))
        }
    }
    return lifted
}

private class BandGesture(private val slop: Float, private val start: Offset) {
    var claimed = false
        private set

    fun handsOver(position: Offset, lifted: Boolean): Boolean {
        val along = abs(position.y - start.y)
        val across = abs(position.x - start.x)
        val sideways = !claimed && across > slop && across > along
        if (along > slop) claimed = true
        return sideways && !lifted
    }
}

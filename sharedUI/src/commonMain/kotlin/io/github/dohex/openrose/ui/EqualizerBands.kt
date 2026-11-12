package io.github.dohex.openrose.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import kotlin.math.roundToInt

/** What each interface style varies on the band editor; layout, gestures and drawing are shared. */
private data class EqualizerTokens(
    val trackWidth: Dp,
    val thumbRadius: Dp,
    val activeThumbRadius: Dp,
    val curveWidth: Dp,
    val springThumbs: Boolean,
)

@Composable
private fun equalizerTokens(): EqualizerTokens = when (LocalInterfaceStyle.current) {
    InterfaceStyle.MIUIX -> EqualizerTokens(4.dp, 6.dp, 8.dp, 2.5.dp, springThumbs = false)
    InterfaceStyle.MATERIAL3 -> EqualizerTokens(6.dp, 8.dp, 11.dp, 3.dp, springThumbs = true)
}

private val BAND_AREA_HEIGHT = 200.dp

/**
 * One vertical control per band on a shared gain axis, with the curve drawn through the thumbs. Pressing anywhere in
 * a band's column moves its thumb there; the band is sent when the finger lifts, or at once from keys and
 * accessibility actions. [active] false draws the curve faded, for a curve the device is not playing.
 */
@Composable
internal fun EqualizerBands(
    curve: List<Int>,
    capabilities: CustomEqualizerCapabilities,
    enabled: Boolean,
    active: Boolean,
    onCommit: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = equalizerTokens()
    val minGain = capabilities.minGain
    val maxGain = capabilities.maxGain
    val haptics = LocalHapticFeedback.current
    var drag by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var focusedBand by remember { mutableStateOf<Int?>(null) }
    val latestCurve by rememberUpdatedState(curve)
    val latestCommit by rememberUpdatedState(onCommit)
    val shown = drag?.let { (band, gain) -> curve.toMutableList().also { it[band] = gain } } ?: curve
    val highlighted = drag?.first ?: focusedBand

    fun commit(band: Int, gain: Int) {
        val clamped = gain.coerceIn(minGain, maxGain)
        if (latestCurve[band] != clamped) latestCommit(latestCurve.toMutableList().also { it[band] = clamped })
    }

    val colors = bandColors(enabled, active)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BandLabels(shown.map(::gainLabel), highlighted, colors.highlight, appColor(AppColorRole.OnSurface))
        Box(Modifier.fillMaxWidth().height(BAND_AREA_HEIGHT)) {
            val thumbGains = thumbGains(shown, drag?.first, eased = tokens.springThumbs)
            Canvas(Modifier.fillMaxWidth().height(BAND_AREA_HEIGHT)) {
                drawBands(thumbGains, minGain, maxGain, highlighted, tokens, colors)
            }
            Row(Modifier.fillMaxWidth().height(BAND_AREA_HEIGHT)) {
                capabilities.bandsHz.forEachIndexed { band, hz ->
                    BandControl(
                        hz = hz,
                        gain = shown[band],
                        minGain = minGain,
                        maxGain = maxGain,
                        enabled = enabled,
                        inset = tokens.activeThumbRadius,
                        onFocusChange = { focusedBand = focusedBand.afterFocusChange(band, it) },
                        onSet = { commit(band, it) },
                        onStep = { commit(band, latestCurve[band] + it) },
                        onDrag = { gain ->
                            if (drag != band to gain) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            drag = band to gain
                        },
                        onRelease = { lifted ->
                            drag?.takeIf { lifted }?.let { (draggedBand, draggedGain) -> commit(draggedBand, draggedGain) }
                            drag = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        BandLabels(capabilities.bandsHz.map(::compactFrequencyLabel), null, colors.highlight, appColor(AppColorRole.Summary))
    }
}

private fun Int?.afterFocusChange(band: Int, focused: Boolean): Int? = if (focused) band else this?.takeIf { it != band }

@Composable
private fun bandColors(enabled: Boolean, active: Boolean): BandColors {
    val primary = appColor(AppColorRole.Primary)
    val curve = when {
        !enabled -> appColor(AppColorRole.DisabledContent)
        active -> primary
        else -> primary.copy(alpha = 0.45f)
    }
    return BandColors(track = appColor(AppColorRole.Divider), highlight = primary, curve = curve)
}

/** One centred label per band column, the [highlighted] one in [highlight]. */
@Composable
private fun BandLabels(labels: List<String>, highlighted: Int?, highlight: Color, normal: Color) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { band, label ->
            AppText(
                label,
                Modifier.weight(1f),
                role = AppTextRole.Footnote,
                color = if (band == highlighted) highlight else normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Thumb positions, eased toward [gains] when [eased]; the [dragged] thumb follows the finger exactly. */
@Composable
private fun thumbGains(gains: List<Int>, dragged: Int?, eased: Boolean): List<Float> = key(gains.size) {
    gains.mapIndexed { band, gain ->
        val animated = animateFloatAsState(
            gain.toFloat(),
            if (eased) spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow) else snap(),
            label = "eqThumb",
        ).value
        if (band == dragged) gain.toFloat() else animated
    }
}

/**
 * One band's input: a slider to accessibility services, ↑/↓ while focused, and press-and-drag along its column.
 * [onRelease] is told whether the gesture ended with the finger lifting, rather than being cancelled.
 */
@Composable
private fun BandControl(
    hz: Int,
    gain: Int,
    minGain: Int,
    maxGain: Int,
    enabled: Boolean,
    inset: Dp,
    onFocusChange: (Boolean) -> Unit,
    onSet: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onDrag: (Int) -> Unit,
    onRelease: (lifted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestRelease by rememberUpdatedState(onRelease)
    Box(
        modifier
            .fillMaxHeight()
            .semantics(mergeDescendants = true) {
                contentDescription = "${equalizerFrequencyLabel(hz)} 增益"
                stateDescription = "${gainLabel(gain)} dB"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    gain.toFloat(),
                    minGain.toFloat()..maxGain.toFloat(),
                    (maxGain - minGain - 1).coerceAtLeast(0),
                )
                if (!enabled) disabled()
                setProgress { requested ->
                    if (enabled) onSet(requested.roundToInt())
                    enabled
                }
            }
            .onFocusChanged { onFocusChange(it.isFocused) }
            .onKeyEvent { event ->
                val step = gainStepFor(event.key) ?: return@onKeyEvent false
                if (enabled && event.type == KeyEventType.KeyDown) onStep(step)
                true
            }
            .focusable(enabled)
            .pointerInput(enabled, minGain, maxGain, inset) {
                if (!enabled) return@pointerInput
                val insetPx = inset.toPx()
                fun gainAt(y: Float): Int {
                    val fraction = ((y - insetPx) / (size.height - 2 * insetPx)).coerceIn(0f, 1f)
                    return (maxGain - fraction * (maxGain - minGain)).roundToInt()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var lifted = false
                    try {
                        latestDrag(gainAt(down.position.y))
                        while (!lifted) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            lifted = !change.pressed
                            if (!lifted) latestDrag(gainAt(change.position.y))
                        }
                    } finally {
                        latestRelease(lifted)
                    }
                }
            },
    )
}

private fun gainStepFor(key: Key): Int? = when (key) {
    Key.DirectionUp -> 1
    Key.DirectionDown -> -1
    else -> null
}

private class BandColors(val track: Color, val highlight: Color, val curve: Color)

/** Gain grid, one track per band, then the curve and its thumbs; thumbs stay inside the area at either limit. */
private fun DrawScope.drawBands(
    gains: List<Float>,
    minGain: Int,
    maxGain: Int,
    highlighted: Int?,
    tokens: EqualizerTokens,
    colors: BandColors,
) {
    val top = tokens.activeThumbRadius.toPx()
    val bottom = size.height - top
    val range = (maxGain - minGain).coerceAtLeast(1).toFloat()
    fun yOf(gain: Float) = top + (maxGain - gain) / range * (bottom - top)
    val columnWidth = size.width / gains.size
    fun xOf(band: Int) = columnWidth * (band + 0.5f)

    val hairline = 1.dp.toPx()
    for (limit in listOf(maxGain, minGain)) {
        val y = yOf(limit.toFloat())
        drawLine(colors.track.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), hairline)
    }
    val zeroY = yOf(0f.coerceIn(minGain.toFloat(), maxGain.toFloat()))
    drawLine(colors.track, Offset(0f, zeroY), Offset(size.width, zeroY), hairline)

    val trackWidth = tokens.trackWidth.toPx()
    gains.indices.forEach { band ->
        drawRoundRect(
            color = if (band == highlighted) colors.highlight.copy(alpha = 0.25f) else colors.track.copy(alpha = 0.5f),
            topLeft = Offset(xOf(band) - trackWidth / 2, top),
            size = Size(trackWidth, bottom - top),
            cornerRadius = CornerRadius(trackWidth / 2),
        )
    }

    val points = gains.mapIndexed { band, gain -> Offset(xOf(band), yOf(gain)) }
    val line = Path().apply {
        moveTo(points.first().x, points.first().y)
        smoothThrough(points)
    }
    val area = Path().apply {
        moveTo(points.first().x, zeroY)
        lineTo(points.first().x, points.first().y)
        smoothThrough(points)
        lineTo(points.last().x, zeroY)
        close()
    }
    drawPath(area, colors.curve.copy(alpha = colors.curve.alpha * 0.15f))
    drawPath(line, colors.curve, style = Stroke(tokens.curveWidth.toPx(), cap = StrokeCap.Round))
    points.forEachIndexed { band, point ->
        val radius = if (band == highlighted) tokens.activeThumbRadius else tokens.thumbRadius
        drawCircle(colors.curve, radius.toPx(), point)
    }
}

/** Joins [points] with horizontal-tangent cubics, so the curve never overshoots a thumb. */
private fun Path.smoothThrough(points: List<Offset>) {
    points.zipWithNext { from, to ->
        val midX = (from.x + to.x) / 2
        cubicTo(midX, from.y, midX, to.y, to.x, to.y)
    }
}

private fun gainLabel(gain: Int): String = if (gain > 0) "+$gain" else "$gain"

private fun compactFrequencyLabel(hz: Int): String = when {
    hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000}k"
    hz >= 1000 -> "${hz / 1000.0}k"
    else -> "$hz"
}

private fun equalizerFrequencyLabel(hz: Int): String = when {
    hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
    hz >= 1000 -> "${hz / 1000.0} kHz"
    else -> "$hz Hz"
}

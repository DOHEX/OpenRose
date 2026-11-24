package io.github.dohex.openrose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

private val BAND_AREA_HEIGHT = 136.dp

private val MIN_BAND_WIDTH = 48.dp

private val SCALE_WIDTH = 28.dp

private val LABEL_GAP = 6.dp

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
    val density = LocalDensity.current
    val drag = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val focusedBand = remember { mutableStateOf<Int?>(null) }
    val latestCurve by rememberUpdatedState(curve)
    val latestCommit by rememberUpdatedState(onCommit)
    val shown: () -> List<Int> = remember {
        { drag.value?.let { (band, gain) -> latestCurve.toMutableList().also { it[band] = gain } } ?: latestCurve }
    }
    val highlighted: () -> Int? = remember { { drag.value?.first ?: focusedBand.value } }

    fun commit(band: Int, gain: Int) {
        val clamped = gain.coerceIn(minGain, maxGain)
        if (latestCurve[band] != clamped) latestCommit(latestCurve.toMutableList().also { it[band] = clamped })
    }

    val colors = bandColors(enabled, active)
    val thumbs = rememberThumbs(curve, shown, { drag.value?.first }, eased = tokens.springThumbs)
    var gainLabelsHeight by remember { mutableIntStateOf(0) }
    Row(modifier) {
        Column(Modifier.widthIn(min = SCALE_WIDTH)) {
            Spacer(Modifier.height(with(density) { gainLabelsHeight.toDp() } + LABEL_GAP))
            GainScale(minGain, maxGain, tokens.activeThumbRadius, Modifier.height(BAND_AREA_HEIGHT))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val count = capabilities.bandsHz.size
            val bandWidth = maxOf(MIN_BAND_WIDTH, maxWidth / count)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(bandWidth * count), verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
                    BandLabels(
                        labels = { shown().map(::gainLabel) },
                        highlighted = highlighted,
                        highlight = colors.highlight,
                        normal = appColor(AppColorRole.OnSurface),
                        bandWidth = bandWidth,
                        modifier = Modifier.onSizeChanged { gainLabelsHeight = it.height },
                    )
                    Box(Modifier.fillMaxWidth().height(BAND_AREA_HEIGHT)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawBands(thumbs.map { it.value }, minGain, maxGain, highlighted(), tokens, colors)
                        }
                        val focusRequesters = remember(count) { List(count) { FocusRequester() } }
                        Row(Modifier.fillMaxSize()) {
                            capabilities.bandsHz.forEachIndexed { band, hz ->
                                BandControl(
                                    hz = hz,
                                    gain = { shown()[band] },
                                    minGain = minGain,
                                    maxGain = maxGain,
                                    enabled = enabled,
                                    focused = focusedBand.value == band,
                                    inset = tokens.activeThumbRadius,
                                    focusRequester = focusRequesters[band],
                                    onFocusChange = { focusedBand.value = focusedBand.value.afterFocusChange(band, it) },
                                    onMoveFocus = { delta ->
                                        val target = (band + delta).coerceIn(0, count - 1)
                                        if (target != band) focusRequesters[target].requestFocus()
                                    },
                                    onSet = { commit(band, it) },
                                    onStep = { commit(band, latestCurve[band] + it) },
                                    onDrag = { gain ->
                                        if (drag.value != band to gain) {
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        }
                                        drag.value = band to gain
                                    },
                                    onRelease = { lifted ->
                                        val dragged = drag.value
                                        if (lifted && dragged != null) commit(dragged.first, dragged.second)
                                        drag.value = null
                                    },
                                    modifier = Modifier.width(bandWidth),
                                )
                            }
                        }
                    }
                    BandLabels(
                        labels = { capabilities.bandsHz.map(::compactFrequencyLabel) },
                        highlighted = { null },
                        highlight = colors.highlight,
                        normal = appColor(AppColorRole.Summary),
                        bandWidth = bandWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun GainScale(minGain: Int, maxGain: Int, inset: Dp, modifier: Modifier) {
    val marks = listOf(maxGain, 0, minGain).filter { it in minGain..maxGain }.distinct()
    Layout(
        content = { marks.forEach { AppText(gainLabel(it), role = AppTextRole.Footnote) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gap = LABEL_GAP.roundToPx()
        val width = ((labels.maxOfOrNull { it.width } ?: 0) + gap).coerceAtLeast(SCALE_WIDTH.roundToPx())
        val height = constraints.maxHeight
        val top = inset.toPx()
        layout(width, height) {
            labels.forEachIndexed { index, label ->
                val y = top + gainFraction(marks[index].toFloat(), minGain, maxGain) * (height - 2 * top)
                label.placeRelative(
                    (width - gap - label.width).coerceAtLeast(0),
                    (y - label.height / 2f).roundToInt().coerceIn(0, (height - label.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

private fun Int?.afterFocusChange(band: Int, focused: Boolean): Int? = if (focused) band else this?.takeIf { it != band }

@Composable
private fun bandColors(enabled: Boolean, active: Boolean): BandColors {
    val primary = appColor(AppColorRole.Primary)
    val thumb = if (enabled) primary else appColor(AppColorRole.DisabledContent)
    val curve = if (enabled && !active) thumb.copy(alpha = 0.45f) else thumb
    return BandColors(track = appColor(AppColorRole.Divider), highlight = primary, curve = curve, thumb = thumb)
}

@Composable
private fun BandLabels(
    labels: () -> List<String>,
    highlighted: () -> Int?,
    highlight: Color,
    normal: Color,
    bandWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val current = highlighted()
    Row(modifier) {
        labels().forEachIndexed { band, label ->
            AppText(
                label,
                Modifier.width(bandWidth),
                role = AppTextRole.Footnote,
                color = if (band == current) highlight else normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberThumbs(
    curve: List<Int>,
    shown: () -> List<Int>,
    dragged: () -> Int?,
    eased: Boolean,
): List<Animatable<Float, AnimationVector1D>> {
    val thumbs = remember(curve.size) { List(curve.size) { Animatable(curve[it].toFloat()) } }
    LaunchedEffect(thumbs, eased) {
        snapshotFlow { shown() to dragged() }.collect { (gains, draggedBand) ->
            gains.forEachIndexed { band, gain ->
                launch {
                    if (!eased || band == draggedBand) {
                        thumbs[band].snapTo(gain.toFloat())
                    } else {
                        thumbs[band].animateTo(gain.toFloat(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                    }
                }
            }
        }
    }
    return thumbs
}

@Composable
private fun BandControl(
    hz: Int,
    gain: () -> Int,
    minGain: Int,
    maxGain: Int,
    enabled: Boolean,
    focused: Boolean,
    inset: Dp,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onMoveFocus: (Int) -> Unit,
    onSet: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onDrag: (Int) -> Unit,
    onRelease: (lifted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestRelease by rememberUpdatedState(onRelease)
    val focusRingColor = appColor(AppColorRole.Primary)
    Box(
        modifier
            .fillMaxHeight()
            .then(
                if (focused && enabled) {
                    Modifier.border(2.dp, focusRingColor, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .focusRequester(focusRequester)
            .bandSemantics(hz, gain, minGain, maxGain, enabled, onSet)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .bandKeys(enabled, minGain, maxGain, onStep, onSet, onMoveFocus)
            .focusable(enabled)
            .bandDrag(enabled, minGain, maxGain, inset, { latestDrag(it) }, { latestRelease(it) }),
    )
}

private fun Modifier.bandSemantics(
    hz: Int,
    gain: () -> Int,
    minGain: Int,
    maxGain: Int,
    enabled: Boolean,
    onSet: (Int) -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    val current = gain()
    contentDescription = "${equalizerFrequencyLabel(hz)} 增益"
    stateDescription = gainLabel(current)
    progressBarRangeInfo = ProgressBarRangeInfo(
        current.toFloat(),
        minGain.toFloat()..maxGain.toFloat(),
        (maxGain - minGain - 1).coerceAtLeast(0),
    )
    if (!enabled) disabled()
    setProgress { requested ->
        if (enabled) onSet(requested.roundToInt())
        enabled
    }
}

private fun Modifier.bandKeys(
    enabled: Boolean,
    minGain: Int,
    maxGain: Int,
    onStep: (Int) -> Unit,
    onSet: (Int) -> Unit,
    onMoveFocus: (Int) -> Unit,
): Modifier = onKeyEvent { event ->
    if (!enabled) return@onKeyEvent false
    val action: (() -> Unit)? = when (event.key) {
        Key.DirectionUp -> ({ onStep(1) })
        Key.DirectionDown -> ({ onStep(-1) })
        Key.DirectionLeft -> ({ onMoveFocus(-1) })
        Key.DirectionRight -> ({ onMoveFocus(1) })
        Key.MoveHome, Key.NumPadMoveHome -> ({ onSet(minGain) })
        Key.MoveEnd, Key.NumPadMoveEnd -> ({ onSet(maxGain) })
        else -> null
    }
    if (action == null) return@onKeyEvent false
    if (event.type == KeyEventType.KeyDown) action()
    true
}

private class BandColors(val track: Color, val highlight: Color, val curve: Color, val thumb: Color)

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
    fun yOf(gain: Float) = top + gainFraction(gain, minGain, maxGain) * (bottom - top)
    val columnWidth = size.width / gains.size
    fun xOf(band: Int): Float {
        val column = if (layoutDirection == LayoutDirection.Rtl) gains.lastIndex - band else band
        return columnWidth * (column + 0.5f)
    }

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
        drawCircle(colors.thumb, radius.toPx(), point)
    }
}

private fun Path.smoothThrough(points: List<Offset>) {
    points.zipWithNext { from, to ->
        val midX = (from.x + to.x) / 2
        cubicTo(midX, from.y, midX, to.y, to.x, to.y)
    }
}

private fun gainFraction(gain: Float, minGain: Int, maxGain: Int): Float = (maxGain - gain) / (maxGain - minGain).coerceAtLeast(1)

private fun gainLabel(gain: Int): String = when {
    gain > 0 -> "+$gain"
    gain < 0 -> "−${-gain}"
    else -> "0"
}

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

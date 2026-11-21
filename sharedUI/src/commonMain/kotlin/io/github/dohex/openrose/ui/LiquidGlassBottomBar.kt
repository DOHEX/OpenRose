/*
 * The tab bar layout and its drag, press and highlight mechanics are adapted from the Backdrop catalog
 * (https://github.com/Kyant0/AndroidLiquidGlass: LiquidBottomTabs.kt, DampedDragAnimation.kt,
 * InteractiveHighlight.kt, DragGestureInspector.kt), Copyright Kyant, licensed under the Apache License 2.0.
 */

package io.github.dohex.openrose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.TimeSource

private val Capsule = RoundedCornerShape(percent = 50)
private val TabWidth = 72.dp
private val BarHeight = 64.dp
private val IndicatorHeight = 56.dp
private val BarPadding = 4.dp
private val IconSize = 28.dp

/** The pager content the liquid-glass bar refracts, or null when [active] is false; it draws [surfaceColor] where pages leave gaps. */
@Composable
internal fun rememberLiquidGlassBackdrop(surfaceColor: Color, active: Boolean): LayerBackdrop? = if (active) {
    rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
} else {
    null
}

internal fun Modifier.liquidGlassContent(backdrop: LayerBackdrop?): Modifier = if (backdrop != null) layerBackdrop(backdrop) else this

/**
 * The Miuix floating capsule in its liquid-glass material: icon-only tabs on a refracting glass capsule, plus a
 * glass indicator that follows the selected page and can be dragged across tabs. [selectedIndex] is owned by the
 * caller; a tap or a finished drag reports the new index through [onSelect], and the indicator then follows.
 * Sits where [top.yukonga.miuix.kmp.basic.FloatingNavigationBar] would, with the same bottom inset rule.
 */
@Composable
internal fun MiuixLiquidGlassBottomBar(
    backdrop: LayerBackdrop,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val tabsCount = mainPages.size
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val accentColor = colors.primary
    val contentColor = colors.onSurfaceContainer
    val containerColor = colors.surfaceContainer.copy(alpha = 0.4f)
    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val tabWidthPx = with(density) { TabWidth.toPx() }
    val barWidthPx = with(density) { (TabWidth * tabsCount + BarPadding * 2).toPx() }
    val animationScope = rememberCoroutineScope()
    val currentOnSelect by rememberUpdatedState(onSelect)

    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density, barWidthPx) {
        derivedStateOf {
            val fraction = (offsetAnimation.value / barWidthPx).coerceIn(-1f, 1f)
            with(density) { 4.dp.toPx() } * fraction.sign * EaseOut.transform(abs(fraction))
        }
    }

    val dragAnimation = remember(animationScope, tabsCount, tabWidthPx, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStopped = {
                val target = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                animateToValue(target.toFloat())
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                currentOnSelect(target)
            },
            onDrag = { _, dragAmount ->
                updateValue(targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
            },
        )
    }
    // Taps and pager swipes change the selection from outside; a finished drag has already aimed here.
    LaunchedEffect(dragAnimation, selectedIndex) {
        if (dragAnimation.targetValue != selectedIndex.toFloat()) {
            dragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    val interactiveHighlight = remember(animationScope, dragAnimation) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size ->
                val center = (dragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                Offset(if (isLtr) center else size.width - center, size.height / 2f)
            },
        )
    }

    val navBarBottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val captionBarBottom = WindowInsets.captionBar.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val bottomPadding = (if (navBarBottom != 0.dp) 26.dp + navBarBottom else 36.dp) + captionBarBottom

    Box(Modifier.fillMaxWidth().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
        Box(Modifier.width(TabWidth * tabsCount + BarPadding * 2).height(BarHeight), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(24.dp.toPx(), 24.dp.toPx())
                        },
                        layerBlock = {
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, dragAnimation.pressProgress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .fillMaxSize()
                    .padding(BarPadding)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                mainPages.forEachIndexed { index, page ->
                    LiquidTab(
                        page = page,
                        tint = contentColor,
                        selected = index == selectedIndex,
                        onClick = { currentOnSelect(index) },
                    )
                }
            }

            // An invisible accent-tinted copy of the tabs; the indicator refracts it so the selected icon turns accent.
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule },
                        effects = {
                            val progress = dragAnimation.pressProgress
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                        },
                        highlight = { Highlight.Default.copy(alpha = dragAnimation.pressProgress) },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(IndicatorHeight)
                    .fillMaxWidth()
                    .padding(horizontal = BarPadding)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                mainPages.forEach { page ->
                    LiquidTab(page = page, tint = accentColor, scale = { lerp(1f, 1.2f, dragAnimation.pressProgress) })
                }
            }

            Box(
                Modifier
                    .padding(horizontal = BarPadding)
                    .graphicsLayer {
                        translationX = if (isLtr) {
                            dragAnimation.value * tabWidthPx + panelOffset
                        } else {
                            size.width - (dragAnimation.value + 1f) * tabWidthPx + panelOffset
                        }
                    }
                    .then(interactiveHighlight.gestureModifier)
                    .then(dragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule },
                        effects = {
                            val progress = dragAnimation.pressProgress
                            lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = dragAnimation.pressProgress) },
                        shadow = { Shadow(alpha = dragAnimation.pressProgress) },
                        innerShadow = {
                            val progress = dragAnimation.pressProgress
                            InnerShadow(radius = 8.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            scaleX = dragAnimation.scaleX
                            scaleY = dragAnimation.scaleY
                            val velocity = dragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dragAnimation.pressProgress
                            drawRect(if (dark) Color.White.copy(0.1f) else Color.Black.copy(0.1f), alpha = 1f - progress)
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        },
                    )
                    .height(IndicatorHeight)
                    .width(TabWidth),
            )
        }
    }
}

/** One icon-only tab. Only the interactive copy passes [onClick]; it alone carries the tab semantics. */
@Composable
private fun RowScope.LiquidTab(
    page: AppPage,
    tint: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    scale: () -> Float = { 1f },
) {
    Box(
        Modifier
            .fillMaxHeight()
            .weight(1f)
            .clip(Capsule)
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = selected,
                        onClick = onClick,
                        role = Role.Tab,
                        interactionSource = null,
                        indication = null,
                    )
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = RoseIcons[page.icon],
            contentDescription = page.navigationLabel,
            modifier = Modifier.size(IconSize),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** Spring-damped value, press and squash state for the draggable indicator. */
private class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedRange<Float>,
    visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private val clockStart = TimeSource.Monotonic.markNow()

    val value: Float get() = valueAnimation.value

    /**
     * Where the indicator is headed, set the moment a drag or selection asks for it. The animation's own target only
     * moves once its launched coroutine runs, so several drag events within one frame would otherwise lose distance.
     */
    var targetValue: Float = initialValue
        private set
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { press() },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    private fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    private fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != valueAnimation.targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        targetValue = target
        animationScope.launch {
            valueAnimation.animateTo(target, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        val target = value.coerceIn(valueRange)
        targetValue = target
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(clockStart.elapsedNow().inWholeMilliseconds, Offset(value, 0f))
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

/** A light glow centred on the indicator while it is held. */
private class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (Size) -> Offset,
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)

    private val pressProgressAnimation = Animatable(0f, 0.001f)

    private val shader = if (isRuntimeShaderSupported()) {
        RuntimeShader(
            """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}""",
        )
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            if (shader != null) {
                drawRect(Color.White.copy(0.08f * progress), blendMode = BlendMode.Plus)
                val center = position(size)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setColorUniform("color", Color.White.copy(0.15f * progress))
                shader.setFloatUniform("radius", size.minDimension * 1.5f)
                shader.setFloatUniform("position", center.x.coerceIn(0f, size.width), center.y.coerceIn(0f, size.height))
                drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { animatePress(1f) },
            onDragEnd = { animatePress(0f) },
            onDragCancel = { animatePress(0f) },
        )
    }

    private fun animatePress(target: Float) {
        animationScope.launch { pressProgressAnimation.animateTo(target, pressProgressAnimationSpec) }
    }
}

/**
 * Drag detection that, unlike detectDragGestures, starts on the first down without touch slop and never consumes,
 * so the indicator's press and highlight run alongside each other on the same pointer.
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = drag(initialDown.id) { onDrag(it, it.positionChange()) }
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed } ?: return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}

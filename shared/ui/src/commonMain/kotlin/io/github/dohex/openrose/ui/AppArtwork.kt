package io.github.dohex.openrose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.ModelProfile

@Composable
internal fun DeviceArtwork(profile: ModelProfile?, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (profile?.batteryLayout == BatteryLayout.EARBUDS_AND_CASE) {
        EarbudsArtwork(modifier, compact)
    } else {
        Box(modifier.fillMaxWidth().height(if (compact) 88.dp else 190.dp), contentAlignment = Alignment.Center) {
            AppIcon(RoseIcon.HEADPHONES, modifier = Modifier.size(if (compact) 44.dp else 86.dp))
        }
    }
}

@Composable
internal fun EarbudsArtwork(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val ink = appColor(AppColorRole.OnSurface).copy(alpha = 0.86f)
    val accent = appColor(AppColorRole.Primary)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 112.dp else 190.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val minSide = minOf(size.width, size.height)
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        if (minSide <= 56.dp.toPx()) {
            val unit = minSide / 76f
            val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val style = EarbudStrokeStyle(ink = ink, stroke = stroke)
            translate(centerX, centerY) {
                drawStaggeredEarbuds(unit, style)
            }
        } else {
            val unit = minOf(size.width / 92f, size.height / 96f)
            val strokeWidth = (unit * 2.1f).coerceIn(1.8.dp.toPx(), 3.2.dp.toPx())
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val halo = Stroke(width = strokeWidth * 2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val style = EarbudStrokeStyle(ink = ink, stroke = stroke, halo = halo)
            translate(centerX, centerY + 4.5f * unit) {
                drawOpenCaseWithEarbuds(unit, accent, style)
            }
        }
    }
}

private data class EarbudStrokeStyle(
    val ink: Color,
    val stroke: Stroke,
    val halo: Stroke? = null,
)

private fun DrawScope.drawStaggeredEarbuds(unit: Float, style: EarbudStrokeStyle) {
    val budUnit = unit * 1.12f
    drawLineEarbud(Offset(-16f * unit, 8f * unit), budUnit, tiltDegrees = 0f, mirrored = false, style = style)
    drawLineEarbud(Offset(16f * unit, -10f * unit), budUnit, tiltDegrees = 0f, mirrored = true, style = style)
}

private fun DrawScope.drawOpenCaseWithEarbuds(unit: Float, accent: Color, style: EarbudStrokeStyle) {
    val lidPath = Path().apply {
        moveTo(-29f * unit, 4f * unit)
        lineTo(-29f * unit, -12f * unit)
        cubicTo(-29f * unit, -18.5f * unit, -23.5f * unit, -23f * unit, -16f * unit, -23f * unit)
        lineTo(16f * unit, -23f * unit)
        cubicTo(23.5f * unit, -23f * unit, 29f * unit, -18.5f * unit, 29f * unit, -12f * unit)
        lineTo(29f * unit, 4f * unit)
    }
    drawPath(lidPath, color = style.ink.copy(alpha = style.ink.alpha * 0.56f), style = style.stroke)

    val budUnit = unit * 0.92f
    drawLineEarbud(Offset(-17f * unit, -20f * unit), budUnit, tiltDegrees = -10f, mirrored = false, style = style)
    drawLineEarbud(Offset(18f * unit, -30f * unit), budUnit, tiltDegrees = -12f, mirrored = true, style = style)

    val lowerCasePath = Path().apply {
        moveTo(-31f * unit, 4f * unit)
        lineTo(31f * unit, 4f * unit)
        lineTo(31f * unit, 19f * unit)
        cubicTo(31f * unit, 29f * unit, 23f * unit, 36f * unit, 12f * unit, 36f * unit)
        lineTo(-12f * unit, 36f * unit)
        cubicTo(-23f * unit, 36f * unit, -31f * unit, 29f * unit, -31f * unit, 19f * unit)
        close()
    }
    drawPath(lowerCasePath, color = Color.Transparent, blendMode = BlendMode.Clear)
    drawPath(lowerCasePath, color = style.ink, style = style.stroke)

    val lipPath = Path().apply {
        moveTo(-8f * unit, 4f * unit)
        cubicTo(-6.5f * unit, 6.6f * unit, 6.5f * unit, 6.6f * unit, 8f * unit, 4f * unit)
    }
    drawPath(lipPath, color = style.ink, style = style.stroke)
    drawCircle(color = accent, radius = 1.9f * unit, center = Offset(0f, 20f * unit))
}

private fun DrawScope.drawLineEarbud(
    origin: Offset,
    unit: Float,
    tiltDegrees: Float,
    mirrored: Boolean,
    style: EarbudStrokeStyle,
) {
    translate(origin.x, origin.y) {
        scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f, pivot = Offset.Zero) {
            rotate(degrees = tiltDegrees, pivot = Offset.Zero) {
                val stemPath = Path().apply {
                    moveTo(-8f * unit, 3f * unit)
                    lineTo(-8f * unit, 22.5f * unit)
                    cubicTo(-8f * unit, 24.4f * unit, -6.6f * unit, 25.5f * unit, -4.8f * unit, 25.5f * unit)
                    lineTo(-3.8f * unit, 25.5f * unit)
                    cubicTo(-2f * unit, 25.5f * unit, -0.6f * unit, 24.4f * unit, -0.6f * unit, 22.5f * unit)
                    lineTo(-0.6f * unit, 6f * unit)
                }
                style.halo?.let { halo ->
                    drawPath(stemPath, color = Color.Transparent, blendMode = BlendMode.Clear)
                    drawPath(stemPath, color = Color.Transparent, style = halo, blendMode = BlendMode.Clear)
                }
                drawPath(stemPath, color = Color.Transparent, blendMode = BlendMode.Clear)
                drawPath(stemPath, color = style.ink, style = style.stroke)
                translate(0f, -3f * unit) {
                    rotate(degrees = 36f, pivot = Offset.Zero) {
                        val headRx = 13.2f * unit
                        val headRy = 9.8f * unit
                        val headTopLeft = Offset(-headRx, -headRy)
                        val headSize = Size(headRx * 2f, headRy * 2f)
                        style.halo?.let { halo ->
                            drawOval(
                                color = Color.Transparent,
                                topLeft = headTopLeft,
                                size = headSize,
                                style = halo,
                                blendMode = BlendMode.Clear,
                            )
                        }
                        drawOval(color = Color.Transparent, topLeft = headTopLeft, size = headSize, blendMode = BlendMode.Clear)
                        drawOval(color = style.ink, topLeft = headTopLeft, size = headSize, style = style.stroke)
                        drawRoundRect(
                            color = style.ink,
                            topLeft = Offset(-6.8f * unit, -4.1f * unit),
                            size = Size(5.8f * unit, 2.8f * unit),
                            cornerRadius = CornerRadius(1.4f * unit),
                        )
                        val speakerRx = 1.9f * unit
                        val speakerRy = 4.1f * unit
                        drawOval(
                            color = style.ink,
                            topLeft = Offset(9.6f * unit - speakerRx, 0.8f * unit - speakerRy),
                            size = Size(speakerRx * 2f, speakerRy * 2f),
                        )
                    }
                }
            }
        }
    }
}

package io.github.dohex.openrose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Flat illustration of a charging case flanked by both earbuds. The shapes are neutral greys
 * mixed from the card surface towards the text colour, so they stay opaque and quiet in every
 * theme; only the case's indicator light carries the accent colour. The composition keeps a
 * fixed aspect centred in whatever box the caller gives it.
 */
@Composable
internal fun EarbudsArtwork(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val surface = when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTheme.colorScheme.surfaceContainer
        InterfaceStyle.MATERIAL3 -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val ink = appColor(AppColorRole.OnSurface)
    // Dark text on a light surface would make near-black buds; keep them a mid grey there.
    val bud = lerp(surface, ink, if (ink.luminance() > 0.5f) 0.75f else 0.6f)
    val tip = lerp(surface, ink, 0.42f)
    val case = lerp(surface, ink, 0.14f)
    val seam = lerp(surface, ink, 0.26f)
    val light = appColor(AppColorRole.Primary)
    Canvas(
        modifier = modifier.fillMaxWidth().height(if (compact) 112.dp else 190.dp),
    ) {
        val unit = minOf(size.width / 1.3f, size.height)
        val centerX = size.width / 2f
        val top = (size.height - unit) / 2f
        val floor = top + unit * 0.82f

        val caseWidth = unit * 0.62f
        val caseHeight = unit * 0.46f
        val caseLeft = centerX - caseWidth / 2f
        val caseTop = floor - caseHeight
        drawRoundRect(
            color = case,
            topLeft = Offset(caseLeft, caseTop),
            size = Size(caseWidth, caseHeight),
            cornerRadius = CornerRadius(caseHeight * 0.38f),
        )
        drawRoundRect(
            color = seam,
            topLeft = Offset(caseLeft, caseTop + caseHeight * 0.34f),
            size = Size(caseWidth, unit * 0.014f),
        )
        drawCircle(
            color = light,
            radius = unit * 0.022f,
            center = Offset(centerX, caseTop + caseHeight * 0.64f),
        )

        val budCenter = Offset(centerX - unit * 0.47f, floor - unit * BUD_STEM)
        drawEarbud(budCenter, unit, bud, tip)
        scale(scaleX = -1f, scaleY = 1f, pivot = Offset(centerX, 0f)) {
            drawEarbud(budCenter, unit, bud, tip)
        }
    }
}

private const val BUD_STEM = 0.36f

/** One upright earbud with its eartip facing +x and its stem ending on the floor line. */
private fun DrawScope.drawEarbud(center: Offset, unit: Float, body: Color, tip: Color) {
    val headRadius = unit * 0.105f
    val stemWidth = unit * 0.08f
    drawCircle(
        color = tip,
        radius = headRadius * 0.5f,
        center = Offset(center.x + headRadius * 0.95f, center.y + headRadius * 0.1f),
    )
    drawRoundRect(
        color = body,
        topLeft = Offset(center.x - headRadius * 0.35f - stemWidth / 2f, center.y),
        size = Size(stemWidth, unit * BUD_STEM),
        cornerRadius = CornerRadius(stemWidth / 2f),
    )
    drawCircle(color = body, radius = headRadius, center = center)
}

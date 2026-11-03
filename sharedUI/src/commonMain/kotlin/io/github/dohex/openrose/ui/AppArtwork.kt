package io.github.dohex.openrose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun EarbudsArtwork(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val primary = appColor(AppColorRole.Primary)
    val onSurface = appColor(AppColorRole.OnSurface)
    val summary = appColor(AppColorRole.Summary)
    Canvas(
        modifier = modifier.fillMaxWidth().height(if (compact) 112.dp else 190.dp),
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val caseWidth = width * if (compact) 0.49f else 0.44f
        val caseHeight = height * 0.27f
        val caseLeft = centerX - caseWidth / 2f
        val caseTop = height * 0.64f

        drawOval(
            color = summary.copy(alpha = 0.16f),
            topLeft = Offset(caseLeft + caseWidth * 0.1f, caseTop + caseHeight * 0.7f),
            size = Size(caseWidth * 0.8f, caseHeight * 0.42f),
        )

        val metal = Brush.linearGradient(
            colors = listOf(
                summary.copy(alpha = 0.12f),
                summary.copy(alpha = 0.28f),
                onSurface.copy(alpha = 0.34f),
                primary.copy(alpha = 0.14f),
            ),
            start = Offset(caseLeft, caseTop),
            end = Offset(caseLeft + caseWidth, caseTop + caseHeight),
        )
        drawRoundRect(
            brush = metal,
            topLeft = Offset(caseLeft, caseTop),
            size = Size(caseWidth, caseHeight),
            cornerRadius = CornerRadius(caseHeight * 0.36f),
        )
        drawRoundRect(
            color = onSurface.copy(alpha = 0.35f),
            topLeft = Offset(caseLeft + caseWidth * 0.035f, caseTop + caseHeight * 0.34f),
            size = Size(caseWidth * 0.93f, caseHeight * 0.024f),
            cornerRadius = CornerRadius(caseHeight * 0.03f),
        )
        drawRoundRect(
            color = summary.copy(alpha = 0.48f),
            topLeft = Offset(centerX - caseWidth * 0.032f, caseTop + caseHeight * 0.68f),
            size = Size(caseWidth * 0.064f, caseHeight * 0.075f),
            cornerRadius = CornerRadius(caseHeight * 0.04f),
        )
        drawCircle(
            color = primary.copy(alpha = 0.72f),
            radius = minOf(caseWidth, caseHeight) * 0.025f,
            center = Offset(centerX, caseTop + caseHeight * 0.86f),
        )

        drawSilverEarbud(
            center = Offset(centerX - width * 0.19f, height * 0.34f),
            scale = height * 0.23f,
            lean = -1f,
            primary = primary,
            onSurface = onSurface,
            summary = summary,
        )
        drawSilverEarbud(
            center = Offset(centerX + width * 0.19f, height * 0.31f),
            scale = height * 0.23f,
            lean = 1f,
            primary = primary,
            onSurface = onSurface,
            summary = summary,
        )
    }
}

private fun DrawScope.drawSilverEarbud(
    center: Offset,
    scale: Float,
    lean: Float,
    primary: Color,
    onSurface: Color,
    summary: Color,
) {
    val headRadius = scale * 0.58f
    val stemWidth = scale * 0.39f
    val stemHeight = scale * 1.22f
    val stemLeft = center.x - stemWidth / 2f + lean * scale * 0.12f
    val stemTop = center.y + headRadius * 0.48f
    val stemBrush = Brush.linearGradient(
        colors = listOf(
            summary.copy(alpha = 0.15f),
            summary.copy(alpha = 0.42f),
            primary.copy(alpha = 0.15f),
        ),
        start = Offset(stemLeft, stemTop),
        end = Offset(stemLeft + stemWidth, stemTop + stemHeight),
    )
    drawRoundRect(
        brush = stemBrush,
        topLeft = Offset(stemLeft, stemTop),
        size = Size(stemWidth, stemHeight),
        cornerRadius = CornerRadius(stemWidth * 0.48f),
    )
    drawRoundRect(
        color = onSurface.copy(alpha = 0.32f),
        topLeft = Offset(stemLeft + stemWidth * 0.18f, stemTop + stemHeight * 0.77f),
        size = Size(stemWidth * 0.64f, stemHeight * 0.035f),
        cornerRadius = CornerRadius(stemWidth * 0.02f),
    )

    val head = Path().apply {
        moveTo(center.x - headRadius * 0.82f, center.y + headRadius * 0.1f)
        cubicTo(
            center.x - headRadius * 1.05f,
            center.y - headRadius * 0.65f,
            center.x - headRadius * 0.42f,
            center.y - headRadius * 1.08f,
            center.x + headRadius * 0.15f,
            center.y - headRadius * 0.95f,
        )
        cubicTo(
            center.x + headRadius * 0.93f,
            center.y - headRadius * 0.8f,
            center.x + headRadius * 1.05f,
            center.y + headRadius * 0.12f,
            center.x + headRadius * 0.62f,
            center.y + headRadius * 0.64f,
        )
        cubicTo(
            center.x + headRadius * 0.15f,
            center.y + headRadius * 1.12f,
            center.x - headRadius * 0.75f,
            center.y + headRadius * 0.86f,
            center.x - headRadius * 0.82f,
            center.y + headRadius * 0.1f,
        )
        close()
    }
    val headBrush = Brush.linearGradient(
        colors = listOf(
            summary.copy(alpha = 0.12f),
            summary.copy(alpha = 0.32f),
            onSurface.copy(alpha = 0.34f),
            primary.copy(alpha = 0.12f),
        ),
        start = Offset(center.x - headRadius, center.y - headRadius),
        end = Offset(center.x + headRadius, center.y + headRadius),
    )
    drawPath(head, headBrush)
    drawCircle(
        color = onSurface.copy(alpha = 0.42f),
        radius = headRadius * 0.16f,
        center = Offset(center.x + headRadius * 0.49f, center.y - headRadius * 0.02f),
    )
    val highlight = Path().apply {
        moveTo(center.x - headRadius * 0.5f, center.y - headRadius * 0.25f)
        cubicTo(
            center.x - headRadius * 0.1f,
            center.y - headRadius * 0.7f,
            center.x + headRadius * 0.42f,
            center.y - headRadius * 0.57f,
            center.x + headRadius * 0.56f,
            center.y - headRadius * 0.28f,
        )
    }
    drawPath(
        highlight,
        primary.copy(alpha = 0.72f),
        style = Stroke(width = headRadius * 0.1f, cap = StrokeCap.Round),
    )
}

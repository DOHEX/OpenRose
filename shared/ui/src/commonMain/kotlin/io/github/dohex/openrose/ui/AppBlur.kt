package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

internal val LocalAppBackdrop = compositionLocalOf<Backdrop?> { null }
internal val LocalBackdropBlurActive = compositionLocalOf { false }

/**
 * The look of every blurred surface (ADR 0007), owned here so a retune touches one file.
 * Liquid glass keeps its own optical blur (a small radius under a lens, see LiquidGlassBottomBar) and
 * shares only [LIQUID_GLASS_TINT].
 */
internal object BlurTokens {
    /** Blur radius in dp of the bars and the floating capsules. */
    const val RADIUS = 25f

    /** Opacity of the `surface` tint over the blurred top bar and the fixed bottom bar. */
    const val BAR_TINT = 0.75f

    /** Opacity of the `surfaceContainer` tint of the Miuix floating capsule. */
    const val MIUIX_FLOATING_TINT = 0.6f

    /** Opacity of the `surfaceContainerHigh` tint of the Material 3 floating toolbar. */
    const val MATERIAL_FLOATING_TINT = 0.85f

    /**
     * Opacity of the `surfaceContainer` fill of the Miuix liquid-glass capsule; high enough to keep icons and
     * labels legible over scrolling content.
     */
    const val LIQUID_GLASS_TINT = 0.65f

    /** Share of a bar's height, from its outer edge, kept at full blur: the bar's own icons and text sit inside it. */
    private const val BAR_HOLD = 0.7f

    /** The top bar is blurred strongest at the window edge and fades out towards the content. */
    val TopBarFade: ProgressiveBlur = ProgressiveBlur.Top.copy(startFraction = BAR_HOLD)

    /** The fixed bottom bar is blurred strongest at the window edge and fades out towards the content. */
    val BottomBarFade: ProgressiveBlur = ProgressiveBlur.Bottom.copy(startFraction = BAR_HOLD)
}

@Composable
internal fun rememberBackdropBlurActive(settings: UiSettings): Boolean {
    val powerSave = isPlatformPowerSaveMode()
    return settings.backgroundBlurEnabled && !powerSave && isRuntimeShaderSupported()
}

@Composable
internal fun rememberAppBackdrop(surfaceColor: Color, active: Boolean): Backdrop? = if (active) {
    rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
} else {
    null
}

/** Records the content a bar blurs; a null [backdrop] records nothing. */
internal fun Modifier.appBackdropContent(backdrop: Backdrop?): Modifier = if (backdrop is LayerBackdrop) layerBackdrop(backdrop) else this

/** The top bar blurs [backdrop] progressively; without one the bar keeps its own solid container. */
@Composable
internal fun Modifier.topBarBackdropBlur(
    backdrop: Backdrop?,
    surfaceColor: Color,
): Modifier = barBackdropBlur(backdrop, surfaceColor, BlurTokens.TopBarFade)

/** The fixed bottom bar blurs [backdrop] progressively; without one the bar keeps its own solid container. */
@Composable
internal fun Modifier.bottomBarBackdropBlur(
    backdrop: Backdrop?,
    surfaceColor: Color,
): Modifier = barBackdropBlur(backdrop, surfaceColor, BlurTokens.BottomBarFade)

/** The tint is blended inside the blur so it fades out with it; a separate background would leave a hard edge. */
@Composable
private fun Modifier.barBackdropBlur(backdrop: Backdrop?, surfaceColor: Color, fade: ProgressiveBlur): Modifier {
    if (backdrop == null) return this
    val colors = remember(surfaceColor) {
        BlurColors(blendColors = listOf(BlendColorEntry(surfaceColor.copy(alpha = BlurTokens.BAR_TINT))))
    }
    return progressiveTextureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = BlurTokens.RADIUS,
        gradient = fade,
        colors = colors,
    )
}

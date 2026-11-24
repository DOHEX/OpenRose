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
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

internal val LocalAppBackdrop = compositionLocalOf<Backdrop?> { null }
internal val LocalBackdropBlurActive = compositionLocalOf { false }

internal object BlurTokens {
    const val RADIUS = 25f

    const val BAR_TINT = 0.75f

    const val MIUIX_FLOATING_TINT = 0.6f

    const val MATERIAL_FLOATING_TINT = 0.85f

    const val LIQUID_GLASS_TINT = 0.65f
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

internal fun Modifier.appBackdropContent(backdrop: Backdrop?): Modifier = if (backdrop is LayerBackdrop) layerBackdrop(backdrop) else this

@Composable
internal fun Modifier.barBackdropBlur(backdrop: Backdrop?, surfaceColor: Color): Modifier {
    if (backdrop == null) return this
    val colors = remember(surfaceColor) {
        BlurColors(blendColors = listOf(BlendColorEntry(surfaceColor.copy(alpha = BlurTokens.BAR_TINT))))
    }
    return textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = BlurTokens.RADIUS,
        colors = colors,
    )
}

package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

internal val LocalAppBackdrop = compositionLocalOf<Backdrop?> { null }
internal val LocalBackdropBlurActive = compositionLocalOf { false }

@Composable
internal fun rememberBackdropBlurActive(settings: UiSettings): Boolean {
    val powerSave = isPlatformPowerSaveMode()
    return settings.backgroundBlurEnabled && !powerSave && isRuntimeShaderSupported()
}

@Composable
internal fun rememberAppBackdrop(surfaceColor: Color, active: Boolean): Backdrop? {
    return if (active) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
}

internal fun Modifier.appBackdropContent(backdrop: Backdrop?, active: Boolean): Modifier {
    return if (active && backdrop is LayerBackdrop) {
        this.layerBackdrop(backdrop)
    } else {
        this
    }
}

internal fun Modifier.appBarBackdropBlur(
    backdrop: Backdrop?,
    surfaceColor: Color,
    active: Boolean,
    blurRadius: Float = 25f,
): Modifier {
    return if (active && backdrop != null) {
        this
            .textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = blurRadius,
            )
            .background(surfaceColor.copy(alpha = 0.75f))
    } else {
        this.background(surfaceColor)
    }
}

internal fun Modifier.appPopupBackdropBlur(
    backdrop: Backdrop?,
    shape: Shape,
    surfaceColor: Color,
    active: Boolean,
    blurRadius: Float = 25f,
): Modifier {
    return if (active && backdrop != null) {
        this
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = blurRadius,
            )
            .background(surfaceColor.copy(alpha = 0.85f), shape)
    } else {
        this.background(surfaceColor, shape)
    }
}

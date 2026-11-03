package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import io.github.dohex.openrose.core.InterfaceStyle

internal enum class RoseIcon {
    HEADPHONES,
    STATUS,
    SETTINGS,
    PLUS,
    MORE,
    BACK,
    CHEVRON,
    NOISE,
    TRANSPARENCY,
    WIND,
    AUTO,
    EQUALIZER,
    TOUCH,
    AUDIO,
    INFO,
    BLUETOOTH,
    CHECK,
    CLOCK,
    MOON,
    SUN,
    LOG,
    DEVICE,
    CLOSE,
    GAME,
}

/** Resolves the same action to the active interface style's official icon set. */
internal object RoseIcons {
    @Composable
    operator fun get(icon: RoseIcon): ImageVector = when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> miuixIcon(icon)
        InterfaceStyle.MATERIAL3 -> materialIcon(icon)
    }

    val Back: ImageVector
        @Composable get() = this[RoseIcon.BACK]
    val Plus: ImageVector
        @Composable get() = this[RoseIcon.PLUS]
}

@Composable
internal fun AppIcon(
    icon: RoseIcon,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color? = null,
) {
    val vector = RoseIcons[icon]
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> top.yukonga.miuix.kmp.basic.Icon(
            imageVector = vector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint ?: top.yukonga.miuix.kmp.theme.LocalContentColor.current,
        )
        InterfaceStyle.MATERIAL3 -> androidx.compose.material3.Icon(
            imageVector = vector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint ?: androidx.compose.material3.LocalContentColor.current,
        )
    }
}

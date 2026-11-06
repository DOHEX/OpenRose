package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.dohex.openrose.ui.resources.*
import org.jetbrains.compose.resources.vectorResource

/** Google Material Symbols Rounded, weight 400, optical size 24, unfilled. */
@Composable
internal fun materialIcon(icon: RoseIcon): ImageVector = vectorResource(
    when (icon) {
        RoseIcon.HEADPHONES -> Res.drawable.msr_headphones
        RoseIcon.STATUS -> Res.drawable.msr_extension
        RoseIcon.SETTINGS -> Res.drawable.msr_settings
        RoseIcon.PLUS -> Res.drawable.msr_add
        RoseIcon.MORE -> Res.drawable.msr_more_vert
        RoseIcon.BACK -> Res.drawable.msr_arrow_back
        RoseIcon.CHEVRON -> Res.drawable.msr_chevron_right
        RoseIcon.NOISE -> Res.drawable.msr_noise_control_off
        RoseIcon.TRANSPARENCY -> Res.drawable.msr_hearing
        RoseIcon.WIND -> Res.drawable.msr_air
        RoseIcon.AUTO -> Res.drawable.msr_auto_awesome
        RoseIcon.EQUALIZER -> Res.drawable.msr_equalizer
        RoseIcon.TOUCH -> Res.drawable.msr_touch_app
        RoseIcon.AUDIO -> Res.drawable.msr_volume_up
        RoseIcon.INFO -> Res.drawable.msr_info
        RoseIcon.BLUETOOTH -> Res.drawable.msr_bluetooth
        RoseIcon.CHECK -> Res.drawable.msr_check
        RoseIcon.CLOCK -> Res.drawable.msr_schedule
        RoseIcon.MOON -> Res.drawable.msr_dark_mode
        RoseIcon.SUN -> Res.drawable.msr_light_mode
        RoseIcon.LOG -> Res.drawable.msr_list_alt
        RoseIcon.DEVICE -> Res.drawable.msr_earbuds
        RoseIcon.CLOSE -> Res.drawable.msr_close
        RoseIcon.GAME -> Res.drawable.msr_sports_esports
        RoseIcon.PALETTE -> Res.drawable.msr_palette
    },
)

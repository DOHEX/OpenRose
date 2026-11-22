package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.dohex.openrose.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.VolumeUp

enum class RoseIcon(internal val material: DrawableResource, internal val miuix: (() -> ImageVector)? = null) {
    HEADPHONES(Res.drawable.msr_headphones),
    STATUS(Res.drawable.msr_monitor_heart, { MiuixIcons.Layers }),
    SETTINGS(Res.drawable.msr_settings, { MiuixIcons.Settings }),
    PLUS(Res.drawable.msr_add, { MiuixIcons.Add }),
    MORE(Res.drawable.msr_more_vert, { MiuixIcons.More }),
    BACK(Res.drawable.msr_arrow_back, { MiuixIcons.Back }),
    CHEVRON(Res.drawable.msr_chevron_right, { MiuixIcons.ChevronForward }),
    NOISE(Res.drawable.msr_noise_control_off),
    TRANSPARENCY(Res.drawable.msr_hearing),
    WIND(Res.drawable.msr_air),
    AUTO(Res.drawable.msr_auto_awesome),
    EQUALIZER(Res.drawable.msr_equalizer, { MiuixIcons.Tune }),
    TOUCH(Res.drawable.msr_touch_app),
    AUDIO(Res.drawable.msr_volume_up, { MiuixIcons.VolumeUp }),
    INFO(Res.drawable.msr_info, { MiuixIcons.Info }),
    BLUETOOTH(Res.drawable.msr_bluetooth),
    CHECK(Res.drawable.msr_check, { MiuixIcons.Basic.Check }),
    CLOCK(Res.drawable.msr_schedule),
    MOON(Res.drawable.msr_dark_mode),
    SUN(Res.drawable.msr_light_mode),
    LOG(Res.drawable.msr_list_alt, { MiuixIcons.Recent }),
    DEVICE(Res.drawable.msr_earbuds),
    CLOSE(Res.drawable.msr_close, { MiuixIcons.Basic.Close }),
    GAME(Res.drawable.msr_sports_esports),
    PALETTE(Res.drawable.msr_palette, { MiuixIcons.Theme }),
    CHECK_CIRCLE(Res.drawable.msr_check_circle),
    ERROR(Res.drawable.msr_error),
    LINK(Res.drawable.msr_link, { MiuixIcons.Link }),
    LINK_OFF(Res.drawable.msr_link_off),
    MODULE(Res.drawable.msr_extension),
    SHIELD(Res.drawable.msr_verified_user),
    SWAP(Res.drawable.msr_swap_horiz),
    DESKTOP(Res.drawable.msr_desktop_windows),
    WARNING(Res.drawable.msr_warning),
    OPEN_IN_NEW(Res.drawable.msr_open_in_new),
}

/** Resolves the same action to the active interface style's official icon set. */
internal object RoseIcons {
    @Composable
    operator fun get(icon: RoseIcon): ImageVector = when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> icon.miuix?.invoke() ?: vectorResource(icon.material)
        InterfaceStyle.MATERIAL3 -> vectorResource(icon.material)
    }

    val Back: ImageVector
        @Composable get() = this[RoseIcon.BACK]
    val Plus: ImageVector
        @Composable get() = this[RoseIcon.PLUS]
}

@Composable
fun AppIcon(
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

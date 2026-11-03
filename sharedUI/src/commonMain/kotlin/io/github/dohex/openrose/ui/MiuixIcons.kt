package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.VolumeUp

@Composable
internal fun miuixIcon(icon: RoseIcon): ImageVector = when (icon) {
    RoseIcon.HEADPHONES -> materialIcon(icon)
    RoseIcon.STATUS -> MiuixIcons.Layers
    RoseIcon.SETTINGS -> MiuixIcons.Settings
    RoseIcon.PLUS -> MiuixIcons.Add
    RoseIcon.MORE -> MiuixIcons.More
    RoseIcon.BACK -> MiuixIcons.Back
    RoseIcon.CHEVRON -> MiuixIcons.ChevronForward
    RoseIcon.NOISE -> materialIcon(icon)
    RoseIcon.TRANSPARENCY -> materialIcon(icon)
    RoseIcon.WIND -> materialIcon(icon)
    RoseIcon.AUTO -> materialIcon(icon)
    RoseIcon.EQUALIZER -> MiuixIcons.Tune
    RoseIcon.TOUCH -> materialIcon(icon)
    RoseIcon.AUDIO -> MiuixIcons.VolumeUp
    RoseIcon.INFO -> MiuixIcons.Info
    RoseIcon.BLUETOOTH -> materialIcon(icon)
    RoseIcon.CHECK -> MiuixIcons.Basic.Check
    RoseIcon.CLOCK -> materialIcon(icon)
    RoseIcon.MOON -> materialIcon(icon)
    RoseIcon.SUN -> materialIcon(icon)
    RoseIcon.LOG -> MiuixIcons.Recent
    RoseIcon.DEVICE -> materialIcon(icon)
    RoseIcon.CLOSE -> MiuixIcons.Basic.Close
    RoseIcon.GAME -> materialIcon(icon)
}

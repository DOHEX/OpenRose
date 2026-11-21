package io.github.dohex.openrose.ui

internal enum class AppPage(
    val title: String,
    val icon: RoseIcon,
    val secondaryRoute: Route?,
    val editsHeadset: Boolean = false,
) {
    HEADPHONES("我的耳机", RoseIcon.HEADPHONES, null),
    STATUS("状态", RoseIcon.STATUS, null),
    SETTINGS("设置", RoseIcon.SETTINGS, null),
    ADD_DEVICE("添加耳机", RoseIcon.PLUS, Route.AddDevice),
    TOUCH("触控操作", RoseIcon.TOUCH, Route.Touch, editsHeadset = true),
    EQUALIZER("均衡器", RoseIcon.EQUALIZER, Route.Equalizer, editsHeadset = true),
    AUDIO("音频与设备", RoseIcon.AUDIO, Route.Audio, editsHeadset = true),
    DETAILS("设备详情", RoseIcon.INFO, Route.Details),
    DIAGNOSTICS("诊断与记录", RoseIcon.LOG, Route.Diagnostics),
    PERSONALIZATION("个性化", RoseIcon.PALETTE, Route.Personalization),
    CIRCULATION_PAIRING("流转配对", RoseIcon.LINK, Route.CirculationPairing),
    ABOUT("关于", RoseIcon.INFO, Route.About),
}

internal val mainPages = AppPage.entries.filter { it.secondaryRoute == null }

package io.github.dohex.openrose.ui

internal enum class AppPage(
    val title: String,
    val icon: RoseIcon,
    val isMainTab: Boolean = false,
    val editsHeadset: Boolean = false,
) {
    HEADPHONES("我的耳机", RoseIcon.HEADPHONES, isMainTab = true),
    STATUS("状态", RoseIcon.STATUS, isMainTab = true),
    SETTINGS("设置", RoseIcon.SETTINGS, isMainTab = true),
    ADD_DEVICE("添加耳机", RoseIcon.PLUS),
    TOUCH("触控操作", RoseIcon.TOUCH, editsHeadset = true),
    EQUALIZER("均衡器", RoseIcon.EQUALIZER, editsHeadset = true),
    AUDIO("音频与设备", RoseIcon.AUDIO, editsHeadset = true),
    DETAILS("设备详情", RoseIcon.INFO),
    DIAGNOSTICS("诊断与记录", RoseIcon.LOG),
    PERSONALIZATION("个性化", RoseIcon.PALETTE),
    CIRCULATION_PAIRING("流转配对", RoseIcon.LINK),
    TRAY_PANEL_SETTINGS("托盘面板控制项", RoseIcon.DESKTOP),
    SYSTEM_INTEGRATION("系统集成", RoseIcon.MODULE),
    ABOUT("关于", RoseIcon.INFO),
}

internal val mainPages = AppPage.entries.filter { it.isMainTab }

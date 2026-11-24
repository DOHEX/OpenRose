package io.github.dohex.openrose.ui.desktop

import io.github.dohex.openrose.ui.TrayPanelSettings

data class DesktopSettings(
    val backgroundControl: Boolean = true,
    val trayPanel: TrayPanelSettings = TrayPanelSettings.DEFAULT,
)

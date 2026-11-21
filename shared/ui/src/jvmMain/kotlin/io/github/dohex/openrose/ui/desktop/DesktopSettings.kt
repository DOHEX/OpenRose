package io.github.dohex.openrose.ui.desktop

/** Desktop-only preferences. Login launch is owned by the system and read through [LoginLaunchControl]. */
data class DesktopSettings(
    /** Closing the window keeps the control connection in the tray, when the tray is available. */
    val backgroundControl: Boolean = true,
)

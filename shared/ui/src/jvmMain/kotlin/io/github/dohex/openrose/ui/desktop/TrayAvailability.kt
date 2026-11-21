package io.github.dohex.openrose.ui.desktop

/** Whether a tray host currently shows OpenRose's icon, so hiding the window keeps it reachable. */
sealed interface TrayAvailability {
    data object Checking : TrayAvailability
    data object Available : TrayAvailability
    data class Unavailable(val reason: String) : TrayAvailability
}

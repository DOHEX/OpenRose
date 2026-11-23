package io.github.dohex.openrose.ui.desktop

sealed interface TrayAvailability {
    data object Checking : TrayAvailability
    data object Available : TrayAvailability
    data class Unavailable(val reason: String) : TrayAvailability
}

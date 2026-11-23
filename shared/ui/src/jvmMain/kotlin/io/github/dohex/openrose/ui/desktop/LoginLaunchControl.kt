package io.github.dohex.openrose.ui.desktop

import kotlinx.coroutines.flow.StateFlow

sealed interface LoginLaunchState {
    data object Reading : LoginLaunchState
    data class Ready(val enabled: Boolean) : LoginLaunchState

    data object DisabledByUser : LoginLaunchState
    data class Unsupported(val reason: String) : LoginLaunchState
}

interface LoginLaunchControl {
    val state: StateFlow<LoginLaunchState>
    fun set(enabled: Boolean)
    fun openSystemSettings()
}

package io.github.dohex.openrose.ui.desktop

import kotlinx.coroutines.flow.StateFlow

/** “登录时启动” as the system records it; the app never keeps its own copy of this choice. */
sealed interface LoginLaunchState {
    data object Reading : LoginLaunchState
    data class Ready(val enabled: Boolean) : LoginLaunchState

    /** The user turned it off in system settings; only they can turn it back on there. */
    data object DisabledByUser : LoginLaunchState
    data class Unsupported(val reason: String) : LoginLaunchState
}

/** “登录时启动” as the system records it, and the two things the user can do about it. */
interface LoginLaunchControl {
    val state: StateFlow<LoginLaunchState>
    fun set(enabled: Boolean)
    fun openSystemSettings()
}

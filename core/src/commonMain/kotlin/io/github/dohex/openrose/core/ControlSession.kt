package io.github.dohex.openrose.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A platform-owned session. Replacing a page or its interface style must not replace this owner.
 * Transport and protocol adapters will own connections; this initial client has no transport.
 */
class ControlSession(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
) {
    private val mutableState = MutableStateFlow(
        ControlState(platform = initialPlatformState, settings = initialSettings.snapshot()),
    )

    val state: StateFlow<ControlState> = mutableState.asStateFlow()

    fun updateSettings(settings: UserSettings) {
        val snapshot = settings.snapshot()
        mutableState.update { it.copy(settings = snapshot) }
    }

    /** Reports current platform facts. Availability never implies an earbud connection. */
    fun updatePlatformState(platformState: PlatformState) {
        mutableState.update { it.copy(platform = platformState) }
    }

    private fun UserSettings.snapshot(): UserSettings = copy(islandTriggers = islandTriggers.toSet())
}

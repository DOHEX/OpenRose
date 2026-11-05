package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.UiSettingsCodec
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Window and appearance changes share this process's single control entry and preference store. */
internal class DesktopRuntime(
    private val preferences: Preferences,
    platform: PlatformState,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val control: ControlRuntime
    private val mutableUiSettings: MutableStateFlow<UiSettings>
    val uiSettings: StateFlow<UiSettings>

    init {
        val saved = preferences.keys().associateWith { preferences.get(it, "") }
        control = LocalControlRuntime(platform, UserSettingsCodec.decode(saved), scope = scope)
        mutableUiSettings = MutableStateFlow(UiSettingsCodec.decode(saved))
        uiSettings = mutableUiSettings.asStateFlow()
    }

    fun updateSettings(settings: UserSettings) {
        control.updateSettings(settings)
        save(UserSettingsCodec.encode(settings))
    }

    fun updateUiSettings(settings: UiSettings) {
        mutableUiSettings.value = settings
        save(UiSettingsCodec.encode(settings))
    }

    private fun save(values: Map<String, String>) {
        values.forEach { (key, value) -> preferences.put(key, value) }
    }
}

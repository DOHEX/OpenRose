package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import io.github.dohex.openrose.transport.bluez.BluezControlTransport
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.UiSettingsCodec
import io.github.dohex.openrose.ui.model.OpenRoseSettingsStore
import io.github.dohex.openrose.ui.model.RuntimeOpenRoseModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.prefs.Preferences

/** Window, tray and appearance changes share this process's single control entry and preference store. */
internal class DesktopRuntime(
    private val preferences: Preferences,
    platform: PlatformState,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val transport: BluezControlTransport? = null,
) {
    private val control: ControlRuntime
    private val availabilityJob: Job?
    private val mutableDesktopSettings: MutableStateFlow<DesktopSettings>
    val model: RuntimeOpenRoseModel
    val desktopSettings: StateFlow<DesktopSettings>

    init {
        val saved = preferences.keys().associateWith { preferences.get(it, "") }
        control = LocalControlRuntime(platform, UserSettingsCodec.decode(saved), transport = transport, scope = scope)
        mutableDesktopSettings = MutableStateFlow(DesktopSettingsCodec.decode(saved))
        desktopSettings = mutableDesktopSettings.asStateFlow()
        availabilityJob = transport?.let {
            scope.launch {
                it.availability.collect { availability ->
                    control.updatePlatformState(control.state.value.platform.copy(transport = availability))
                }
            }
        }
        val store = object : OpenRoseSettingsStore {
            private val mutableUiSettings = MutableStateFlow(UiSettingsCodec.decode(saved))
            override val uiSettings: StateFlow<UiSettings> = mutableUiSettings.asStateFlow()

            override fun updateSettings(settings: UserSettings) {
                control.updateSettings(settings)
                save(UserSettingsCodec.encode(settings))
            }

            override fun updateUiSettings(settings: UiSettings) {
                mutableUiSettings.value = settings
                save(UiSettingsCodec.encode(settings))
            }
        }
        model = RuntimeOpenRoseModel(control, store, scope)
    }

    fun updateDesktopSettings(settings: DesktopSettings) {
        mutableDesktopSettings.value = settings
        save(DesktopSettingsCodec.encode(settings))
    }

    suspend fun close() {
        availabilityJob?.cancelAndJoin()
        control.updatePlatformState(
            control.state.value.platform.copy(
                transport = TransportAvailability.Unavailable("应用正在关闭"),
            ),
        )
        try {
            try {
                control.stopScan()
            } finally {
                control.disconnect()
            }
        } finally {
            try {
                transport?.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun save(values: Map<String, String>) {
        values.forEach { (key, value) -> preferences.put(key, value) }
    }
}

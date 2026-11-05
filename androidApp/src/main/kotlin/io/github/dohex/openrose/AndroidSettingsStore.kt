package io.github.dohex.openrose

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.UiSettingsCodec

internal data class AppSettings(
    val host: HostSettings = HostSettings(),
    val ui: UiSettings = UiSettings(),
)

/** App and host preferences share one platform store; each codec reads and writes only its own keys. */
internal class AndroidSettingsStore(private val preferences: () -> SharedPreferences) {
    fun loadHost(): HostSettings = HostSettingsCodec.decode(values())

    fun load(): AppSettings {
        val snapshot = values()
        return AppSettings(HostSettingsCodec.decode(snapshot), UiSettingsCodec.decode(snapshot))
    }

    fun saveHost(settings: HostSettings, commit: Boolean = false): Boolean =
        save(HostSettingsCodec.encode(settings), commit)

    fun saveUi(settings: UiSettings) {
        save(UiSettingsCodec.encode(settings), commit = false)
    }

    private fun values(): Map<String, String> = preferences().all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    private fun save(values: Map<String, String>, commit: Boolean): Boolean {
        val editor = preferences().edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        if (commit) return editor.commit()
        editor.apply()
        return true
    }
}

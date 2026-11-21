package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.core.content.edit
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

    fun saveHost(settings: HostSettings, commit: Boolean = false): Boolean = save(HostSettingsCodec.encode(settings), commit)

    fun saveUi(settings: UiSettings) {
        save(UiSettingsCodec.encode(settings), commit = false)
    }

    private fun values(): Map<String, String> = HostSettingsCodec.stringEntries(preferences().all)

    private fun save(values: Map<String, String>, commit: Boolean): Boolean {
        val prefs = preferences()
        if (commit) return prefs.commitEntries(values)
        prefs.edit { values.forEach { (key, value) -> putString(key, value) } }
        return true
    }
}

/** Ownership handoff and host publication require the acknowledgement from SharedPreferences.commit(). */
@SuppressLint("UseKtx") // The KTX edit(commit = true) overload discards the Boolean acknowledgement.
internal fun SharedPreferences.commitEntries(values: Map<String, String?>): Boolean = edit().apply {
    values.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
}.commit()

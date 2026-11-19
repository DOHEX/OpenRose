package io.github.dohex.openrose.bridge

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec

/** The settings the application published to the module's preferences, as the module host reads them. */
fun readHostSettings(prefs: SharedPreferences?): HostSettings {
    val values = HostSettingsCodec.stringEntries(runCatching { prefs?.all }.getOrNull().orEmpty())
    return HostSettingsCodec.decode(values)
}

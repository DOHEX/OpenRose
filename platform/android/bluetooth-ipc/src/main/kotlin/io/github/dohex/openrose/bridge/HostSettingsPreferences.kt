package io.github.dohex.openrose.bridge

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec

/** The settings the application published to the module's preferences, as the module host reads them. */
fun readHostSettings(prefs: SharedPreferences?): HostSettings? {
    val values = runCatching { prefs?.all }.getOrNull() ?: return null
    return HostSettingsCodec.decode(HostSettingsCodec.stringEntries(values))
}

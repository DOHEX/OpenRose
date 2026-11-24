package io.github.dohex.openrose.bridge

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec

fun readHostSettings(prefs: SharedPreferences?): HostSettings? {
    val values = runCatching { prefs?.all }.getOrNull() ?: return null
    return HostSettingsCodec.decode(HostSettingsCodec.stringEntries(values))
}

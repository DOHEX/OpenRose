package io.github.dohex.openrose.framework

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec
import io.github.dohex.openrose.androidintegration.SerializedPublisher
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.commitEntries

internal class ModuleSettingsPublisher(preferences: () -> SharedPreferences?) {
    private val publisher = SerializedPublisher<HostSettings> { settings ->
        val prefs = preferences() ?: return@SerializedPublisher false
        prefs.commitEntries(HostSettingsCodec.encode(settings) + (BridgeWire.CONFIG_REVISION to System.nanoTime().toString()))
    }

    fun publish(settings: () -> HostSettings): Boolean = publisher.publish(settings)
}

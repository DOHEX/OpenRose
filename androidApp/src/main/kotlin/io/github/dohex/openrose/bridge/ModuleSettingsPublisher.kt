package io.github.dohex.openrose.bridge

import android.content.SharedPreferences
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec
import io.github.dohex.openrose.androidintegration.SerializedPublisher
import io.github.dohex.openrose.androidintegration.wire.BridgeWire

/** Serializes writes and derives the current persisted settings only when the writer is held. */
internal class ModuleSettingsPublisher(preferences: () -> SharedPreferences?) {
    private val publisher = SerializedPublisher<HostSettings> { settings ->
        val editor = preferences()?.edit() ?: return@SerializedPublisher false
        HostSettingsCodec.encode(settings).forEach { (key, value) -> editor.putString(key, value) }
        editor.putString(BridgeWire.CONFIG_REVISION, System.nanoTime().toString())
        editor.commit()
    }

    fun publish(settings: () -> HostSettings): Boolean = publisher.publish(settings)
}

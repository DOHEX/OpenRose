package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

@DBusInterfaceName("org.bluez.Bearer.BREDR1")
internal interface ClassicBearer : DBusInterface {
    @Suppress("FunctionNaming")
    fun Disconnect()
}

@DBusInterfaceName("org.bluez.Bearer.LE1")
internal interface LeBearer : DBusInterface {
    @Suppress("FunctionNaming")
    fun Disconnect()
}

internal const val CLASSIC_BEARER = "org.bluez.Bearer.BREDR1"
internal const val LE_BEARER = "org.bluez.Bearer.LE1"
internal const val AUDIO_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"

/** Explicit user handoff is the only operation allowed to release system audio. */
class BluezAudioProfiles internal constructor(private val bus: suspend () -> BluezBus) {
    suspend fun isConnected(address: String): Boolean {
        val objects = bus().objects()
        val path = objects.path(address)
        return objects.hasMedia(address) || objects[path]?.get(CLASSIC_BEARER)?.get("Connected") == true
    }

    suspend fun acquire(address: String) {
        val current = bus()
        val path = checkNotNull(current.objects().path(address)) { "Linux 尚未配对这副耳机" }
        current.connectAudio(path)
        val ready = withTimeoutOrNull(10_000L) {
            while (!current.objects().hasMedia(address)) delay(150)
            true
        }
        check(ready == true) { "Linux 接入耳机音频超时" }
    }

    suspend fun release(address: String) {
        val current = bus()
        val objects = current.objects()
        val path = checkNotNull(objects.path(address)) { "未找到正在让出的耳机" }
        current.disconnectAudio(path, objects.getValue(path))
        val released = withTimeoutOrNull(10_000L) {
            while (isConnected(address)) delay(150)
            true
        }
        check(released == true) { "Linux 尚未让出耳机音频" }
    }
}

private fun BluezObjects.path(address: String): String? = entries.firstOrNull { (_, interfaces) ->
    val device = interfaces[DEVICE]
    device?.get("Paired") == true && normalizeBluetoothAddress(device["Address"] as? String) == address
}?.key

private fun BluezObjects.hasMedia(address: String): Boolean =
    address in audioSnapshot(this, setOf(address)).profiles[AudioProfile.A2DP]?.connectedAddresses.orEmpty()

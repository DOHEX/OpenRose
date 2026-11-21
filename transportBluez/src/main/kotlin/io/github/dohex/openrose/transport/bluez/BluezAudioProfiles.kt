package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.bluez.Device1
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface

@DBusInterfaceName("org.bluez.Bearer.BREDR1")
internal interface ClassicBearer : DBusInterface {
    @DBusMemberName("Disconnect")
    fun disconnect()
}

@DBusInterfaceName("org.bluez.Bearer.LE1")
internal interface LeBearer : DBusInterface {
    @DBusMemberName("Disconnect")
    fun disconnect()
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

internal class NativeBluezAudioBus(private val connection: DBusConnection) : BluezAudioBus {
    override suspend fun connectAudio(path: String) = NativeBluezBus.call {
        try {
            connection.getRemoteObject("org.bluez", path, Device1::class.java).ConnectProfile(AUDIO_SINK_UUID)
        } catch (_: org.bluez.Error.AlreadyConnected) {
            // The caller verifies that MediaTransport1 actually exists.
        } catch (_: org.bluez.Error.InProgress) {
            // A system connection is already in flight. Wait for its actual result.
        }
    }
    override suspend fun disconnectAudio(path: String, interfaces: Map<String, Map<String, Any?>>) = NativeBluezBus.call {
        if (CLASSIC_BEARER in interfaces && LE_BEARER in interfaces) {
            if (interfaces[CLASSIC_BEARER]?.get("Connected") == true) {
                connection.getRemoteObject("org.bluez", path, ClassicBearer::class.java).disconnect()
            }
            if (interfaces[LE_BEARER]?.get("Connected") == true) {
                try {
                    connection.getRemoteObject("org.bluez", path, LeBearer::class.java).disconnect()
                } catch (_: org.bluez.Error.NotConnected) {
                    // LE may already have followed the classic audio disconnect.
                }
            }
        } else {
            connection.getRemoteObject("org.bluez", path, Device1::class.java).Disconnect()
        }
    }
}

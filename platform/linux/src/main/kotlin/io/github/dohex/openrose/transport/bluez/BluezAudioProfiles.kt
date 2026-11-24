package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bluez.Device1
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface

@DBusInterfaceName("org.bluez.Bearer.LE1")
internal interface LeBearer : DBusInterface {
    @DBusMemberName("Connect")
    fun connect()

    @DBusMemberName("Disconnect")
    fun disconnect()
}

@DBusInterfaceName("org.bluez.Bearer.BREDR1")
internal interface ClassicBearer : DBusInterface {
    @DBusMemberName("Disconnect")
    fun disconnect()
}

internal const val CLASSIC_BEARER = "org.bluez.Bearer.BREDR1"
internal const val LE_BEARER = "org.bluez.Bearer.LE1"
internal const val AUDIO_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"

class BluezAudioProfiles internal constructor(
    private val releasedLinks: ReleasedBluezLinks = ReleasedBluezLinks(),
    private val bus: suspend () -> BluezBus,
) {
    suspend fun isConnected(address: String): Boolean = bus().objects().connectedPaths(address).isNotEmpty()

    suspend fun isMediaConnected(address: String) = bus().objects().hasMedia(address)

    suspend fun acquire(address: String) {
        val current = bus()
        val path = checkNotNull(current.objects().devicePath(address, pairedOnly = true)) { "Linux 尚未在已开启的适配器上配对这副耳机" }
        releasedLinks.resumeLocal(path)
        val objects = current.objects()
        if (objects.hasMedia(address)) return

        val interfaces = objects.getValue(path)
        val classic = interfaces[CLASSIC_BEARER]?.get("Connected") ?: interfaces[DEVICE]?.get("Connected")
        val startLe = classic == false && interfaces[LE_BEARER]?.get("Connected") == false
        val leOwner = current.owner
        var openedLe = false
        runCatching {
            if (startLe) {
                currentCoroutineContext().ensureActive()
                checkNotNull(leOwner) { "BlueZ 服务不可用" }
                withContext(NonCancellable) { openedLe = current.requestLe(path) }
                currentCoroutineContext().ensureActive()
                awaitConnection("Linux 接入耳机 LE 链路超时") { current.objects()[path]?.get(LE_BEARER)?.get("Connected") == true }
            }
            current.awaitAudioProfile(path, address)
        }.onFailure { failure ->
            if (openedLe && leOwner != null) {
                withContext(NonCancellable) {
                    runCatching { current.disconnectLe(path, leOwner) }.onFailure(failure::addSuppressed)
                }
            }
        }.getOrThrow()
    }

    private suspend fun BluezBus.requestLe(path: String): Boolean = try {
        connectGatt(path, useLeBearer = true)
        true
    } catch (_: org.bluez.Error.AlreadyConnected) {
        false
    } catch (_: org.bluez.Error.InProgress) {
        false
    }

    private suspend fun BluezBus.awaitAudioProfile(path: String, address: String) {
        var accepted = false
        awaitConnection("Linux 接入耳机音频超时") {
            val ready = objects().hasMedia(address)
            if (!ready && !accepted) {
                accepted = try {
                    connectAudio(path)
                    true
                } catch (_: org.bluez.Error.InProgress) {
                    false
                } catch (_: org.bluez.Error.AlreadyConnected) {
                    true
                }
            }
            ready
        }
    }

    private suspend fun awaitConnection(timeoutMessage: String, connected: suspend () -> Boolean) {
        val ready = withTimeoutOrNull(10_000L) {
            while (!connected()) delay(150)
            true
        }
        check(ready == true) { timeoutMessage }
    }

    suspend fun release(address: String) {
        val current = bus()
        val objects = current.objects()
        checkNotNull(objects.devicePath(address, pairedOnly = true)) { "未找到正在让出的耳机" }
        releasedLinks.release(current, objects.connectedPaths(address)) {
            awaitConnection("Linux 尚未让出耳机的音频或蓝牙链路") { current.objects().connectedPaths(address).isEmpty() }
        }
    }

    private fun BluezObjects.connectedPaths(address: String): List<String> = keys.filter { path ->
        val interfaces = getValue(path)
        val device = interfaces[DEVICE] ?: return@filter false
        onPoweredAdapter(path) && normalizeBluetoothAddress(device["Address"] as? String) == address &&
            (hasMediaTransport(path) || interfaces.hasConnectedBearer(device))
    }

    private fun Map<String, Map<String, Any?>>.hasConnectedBearer(device: Map<String, Any?>): Boolean {
        val classic = get(CLASSIC_BEARER)?.get("Connected") as? Boolean
        val le = get(LE_BEARER)?.get("Connected") as? Boolean
        return when {
            classic == true || le == true -> true
            classic == false && le == false -> false
            else -> checkNotNull(device["Connected"] as? Boolean) { "无法确认耳机蓝牙链路状态" }
        }
    }
}

internal class NativeBluezAudioBus(
    private val deviceAt: (String, String) -> Device1,
    private val classicAt: (String, String) -> ClassicBearer,
    private val leAt: (String, String) -> LeBearer,
) : BluezAudioBus {
    constructor(connection: DBusConnection) : this(
        { owner, path -> connection.getRemoteObject(owner, path, Device1::class.java) },
        { owner, path -> connection.getRemoteObject(owner, path, ClassicBearer::class.java) },
        { owner, path -> connection.getRemoteObject(owner, path, LeBearer::class.java) },
    )

    override suspend fun connectAudio(path: String) = NativeBluezBus.call {
        deviceAt("org.bluez", path).ConnectProfile(AUDIO_SINK_UUID)
    }
    override suspend fun disconnectAudio(path: String, owner: String, interfaces: Map<String, Map<String, Any?>>) {
        val classic = interfaces[CLASSIC_BEARER]?.get("Connected") as? Boolean
        val le = interfaces[LE_BEARER]?.get("Connected") as? Boolean
        if (classic == null || le == null) {
            disconnectIfConnected { deviceAt(owner, path).Disconnect() }
            return
        }
        if (classic) disconnectIfConnected { classicAt(owner, path).disconnect() }
        if (le) disconnectLe(path, owner)
    }
    override suspend fun disconnectLe(path: String, owner: String) = disconnectIfConnected { leAt(owner, path).disconnect() }

    private suspend fun disconnectIfConnected(disconnect: () -> Unit) = NativeBluezBus.call {
        try {
            disconnect()
        } catch (_: org.bluez.Error.NotConnected) {
        }
    }
}

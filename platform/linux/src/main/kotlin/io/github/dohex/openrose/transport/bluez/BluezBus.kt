package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import kotlinx.coroutines.flow.Flow

typealias BluezObjects = Map<String, Map<String, Map<String, Any?>>>

internal fun BluezObjects.onPoweredAdapter(devicePath: String): Boolean {
    val adapter = this[devicePath.substringBeforeLast('/')]?.get(ADAPTER)
    return adapter?.get("Powered") == true
}

/** Any MediaTransport1 owned by this Device1, across all audio profiles. */
internal fun BluezObjects.hasMediaTransport(devicePath: String): Boolean = any { (path, interfaces) ->
    interfaces[MEDIA_TRANSPORT]?.let { mediaBelongsTo(devicePath, path, it) } == true
}

internal fun mediaBelongsTo(devicePath: String, mediaPath: String, properties: Map<String, Any?>): Boolean {
    val owner = properties["Device"] as? String ?: return mediaPath == devicePath || mediaPath.startsWith("$devicePath/")
    return owner == devicePath
}

/** BlueZ exposes a separate Device1 for each adapter that has seen the same address. */
internal fun BluezObjects.devicePath(address: String, pairedOnly: Boolean = false, scanAdapter: String? = null): String? {
    val candidates = keys.filter { path ->
        val device = getValue(path)[DEVICE] ?: return@filter false
        onPoweredAdapter(path) && (!pairedOnly || device["Paired"] == true) &&
            normalizeBluetoothAddress(device["Address"] as? String) == address
    }.sorted()
    if (candidates.size <= 1) return candidates.firstOrNull()
    return candidates.firstOrNull { hasMediaTransport(it) }
        ?: candidates.firstOrNull { getValue(it)[CLASSIC_BEARER]?.get("Connected") == true }
        ?: candidates.firstOrNull { getValue(it)[LE_BEARER]?.get("Connected") == true }
        ?: candidates.firstOrNull { getValue(it)[DEVICE]?.get("Connected") == true }
        ?: candidates.firstOrNull { it.substringBeforeLast('/') == scanAdapter }
        ?: candidates.first()
}

internal sealed interface BluezSignal {
    data class Changed(val path: String, val interfaceName: String, val values: Map<String, Any?>) : BluezSignal
    data class Added(val path: String, val interfaces: Map<String, Map<String, Any?>>) : BluezSignal
    data class Removed(val path: String, val interfaces: Set<String>) : BluezSignal
    data class PrepareForSleep(val sleeping: Boolean) : BluezSignal
    data class Owner(val present: Boolean) : BluezSignal
}

internal interface BluezAudioBus {
    suspend fun connectAudio(path: String)
    suspend fun disconnectAudio(path: String, owner: String, interfaces: Map<String, Map<String, Any?>>)
    suspend fun disconnectLe(path: String, owner: String = "org.bluez")
}

internal interface BluezBus : BluezAudioBus {
    /** Unique D-Bus name of the process serving org.bluez. */
    val owner: String?
    fun signals(accepts: (BluezSignal) -> Boolean): Flow<BluezSignal>
    suspend fun objects(): BluezObjects
    suspend fun connectGatt(path: String, useLeBearer: Boolean)
    suspend fun startNotify(owner: String, path: String)
    suspend fun stopNotify(owner: String, path: String)
    suspend fun write(path: String, value: ByteArray, withResponse: Boolean)
    suspend fun startDiscovery(path: String)
    suspend fun stopDiscovery(path: String)
    suspend fun close()
}

internal const val DEVICE = "org.bluez.Device1"
internal const val ADAPTER = "org.bluez.Adapter1"
internal const val SERVICE = "org.bluez.GattService1"
internal const val CHARACTERISTIC = "org.bluez.GattCharacteristic1"
internal const val MEDIA_TRANSPORT = "org.bluez.MediaTransport1"

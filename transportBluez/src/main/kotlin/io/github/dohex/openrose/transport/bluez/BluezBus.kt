package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.flow.Flow

typealias BluezObjects = Map<String, Map<String, Map<String, Any?>>>

internal sealed interface BluezSignal {
    data class Changed(val path: String, val interfaceName: String, val values: Map<String, Any?>) : BluezSignal
    data class Added(val path: String, val interfaces: Set<String>) : BluezSignal
    data class Removed(val path: String, val interfaces: Set<String>) : BluezSignal
    data class Owner(val present: Boolean) : BluezSignal
}

internal interface BluezBus {
    val events: Flow<BluezSignal>
    suspend fun objects(): BluezObjects
    suspend fun connect(path: String)
    suspend fun startNotify(path: String)
    suspend fun stopNotify(path: String)
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

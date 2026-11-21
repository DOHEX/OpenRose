package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

internal class FakeBluezBus : BluezBus {
    override var owner: String? = ":1.1"
    val events = MutableSharedFlow<BluezSignal>(extraBufferCapacity = 64)
    override fun signals(accepts: (BluezSignal) -> Boolean) = events.filter(accepts)
    var connectFailure: Exception? = null
    var discoveryFailure: Exception? = null
    var stopDiscoveryFailure: Exception? = null
    var objectsFailure: Exception? = null
    var onStartDiscovery: () -> Unit = {}
    val connectBearers = mutableListOf<Boolean>()
    var connects = 0
    var onConnect: suspend (Int) -> Unit = {}
    var onWrite: suspend (ByteArray) -> Unit = {}
    var onStartNotify: () -> Unit = {}
    var onStopNotify: (String) -> Unit = {}
    val notifications = mutableListOf<String>()
    val writes = mutableListOf<Triple<String, ByteArray, Boolean>>()
    var scans = 0
    var scanStops = 0
    var snapshot: BluezObjects = emptyMap()
    override suspend fun objects(): BluezObjects {
        objectsFailure?.let { throw it }
        return snapshot
    }
    var onAudioConnect: (String) -> Unit = {}
    var onAudioDisconnect: (String) -> Unit = {}
    var onLeDisconnect: suspend (String) -> Unit = {}
    override suspend fun disconnectLe(path: String, owner: String) {
        check(owner == this.owner || owner == "org.bluez")
        onLeDisconnect(path)
    }
    override suspend fun connectAudio(path: String) = onAudioConnect(path)
    override suspend fun disconnectAudio(path: String, owner: String, interfaces: Map<String, Map<String, Any?>>) {
        check(owner == this.owner || owner == "org.bluez")
        onAudioDisconnect(path)
    }
    override suspend fun connectGatt(path: String, useLeBearer: Boolean) {
        connectBearers.add(useLeBearer)
        connects++
        connectFailure?.let { throw it }
        onConnect(connects)
    }
    override suspend fun startNotify(owner: String, path: String) {
        check(owner == this.owner)
        onStartNotify()
        notifications.add("start:$path")
    }
    override suspend fun stopNotify(owner: String, path: String) {
        check(owner == this.owner)
        notifications.add("stop:$path")
        onStopNotify(path)
    }
    override suspend fun write(path: String, value: ByteArray, withResponse: Boolean) {
        writes.add(Triple(path, value.copyOf(), withResponse))
        onWrite(value)
    }
    override suspend fun startDiscovery(path: String) {
        scans++
        discoveryFailure?.let { throw it }
        onStartDiscovery()
    }
    override suspend fun stopDiscovery(path: String) {
        scanStops++
        stopDiscoveryFailure?.let { throw it }
    }
    override suspend fun close() = Unit
}

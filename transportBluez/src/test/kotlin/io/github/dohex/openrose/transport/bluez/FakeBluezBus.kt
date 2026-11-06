package io.github.dohex.openrose.transport.bluez

import kotlinx.coroutines.flow.MutableSharedFlow

internal class FakeBluezBus : BluezBus {
    override val events = MutableSharedFlow<BluezSignal>(extraBufferCapacity = 64)
    var connectFailure: Exception? = null
    var discoveryFailure: Exception? = null
    var stopDiscoveryFailure: Exception? = null
    var objectsFailure: Exception? = null
    var onStartDiscovery: () -> Unit = {}
    var connects = 0
    var onConnect: suspend (Int) -> Unit = {}
    var onWrite: suspend (ByteArray) -> Unit = {}
    var onStopNotify: (String) -> Unit = {}
    val notifications = mutableListOf<String>()
    val writes = mutableListOf<Triple<String, ByteArray, Boolean>>()
    var scans = 0
    var scanStops = 0
    var snapshot: BluezObjects = emptyMap()
    override suspend fun objects(): BluezObjects { objectsFailure?.let { throw it }; return snapshot }
    override suspend fun connect(path: String) { connects++; connectFailure?.let { throw it }; onConnect(connects) }
    override suspend fun startNotify(path: String) { notifications.add("start:$path") }
    override suspend fun stopNotify(path: String) { notifications.add("stop:$path"); onStopNotify(path) }
    override suspend fun write(path: String, value: ByteArray, withResponse: Boolean) { writes.add(Triple(path, value.copyOf(), withResponse)); onWrite(value) }
    override suspend fun startDiscovery(path: String) { scans++; discoveryFailure?.let { throw it }; onStartDiscovery() }
    override suspend fun stopDiscovery(path: String) { scanStops++; stopDiscoveryFailure?.let { throw it } }
    override suspend fun close() = Unit
}

package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Linux BlueZ transport. The supplied scope owns initialization and event observation. */
class BluezControlTransport private constructor(scope: CoroutineScope, private val openBus: suspend () -> BluezBus) : ControlTransport {
    constructor(scope: CoroutineScope) : this(scope, { NativeBluezBus.open() })
    internal constructor(scope: CoroutineScope, bus: BluezBus) : this(scope, { bus })

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val mutableAvailability = MutableStateFlow<TransportAvailability>(TransportAvailability.Unavailable("正在初始化 BlueZ"))
    val availability: StateFlow<TransportAvailability> = mutableAvailability.asStateFlow()

    @Volatile private var bus: BluezBus? = null
    private val scanLock = Mutex()
    private val audio = AudioFacts(BluezAudioDriver())
    override val supportsInitializationRecovery = true

    init {
        work.launch {
            try {
                val current = openBus()
                bus = current
                // Subscribe before the first object snapshot, including BlueZ owner changes.
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    current.signals { it.affects(availabilityInterfaces) }.collect { updateAvailability(current) }
                }
                updateAvailability(current)
                ready.complete(Unit)
                observer.join()
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                mutableAvailability.value = TransportAvailability.Unavailable(
                    "BlueZ 运行环境缺少依赖：${error.message ?: error.javaClass.simpleName}",
                )
            } catch (error: Exception) {
                mutableAvailability.value = unavailable(error)
            } finally {
                ready.complete(Unit)
            }
        }
    }
    suspend fun awaitInitialization() {
        ready.await()
    }
    private suspend fun activeBus(): BluezBus {
        ready.await()
        val current = bus ?: throw IllegalStateException((availability.value as? TransportAvailability.Unavailable)?.reason ?: "BlueZ 不可用")
        when (val status = availability.value) {
            TransportAvailability.Available -> return current
            TransportAvailability.Disabled -> throw IllegalStateException("蓝牙适配器已关闭")
            is TransportAvailability.Unavailable -> throw IllegalStateException(status.reason)
        }
    }
    private suspend fun updateAvailability(current: BluezBus) {
        mutableAvailability.value = try {
            val adapters = current.objects().values.mapNotNull { it[ADAPTER] }
            when {
                adapters.isEmpty() -> TransportAvailability.Unavailable("未找到蓝牙适配器")
                adapters.none { it["Powered"] == true } -> TransportAvailability.Unavailable("蓝牙适配器已关闭")
                else -> TransportAvailability.Available
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            unavailable(error)
        }
    }
    private fun unavailable(error: Exception): TransportAvailability.Unavailable {
        val name = error.javaClass.simpleName
        val reason = when {
            name.contains("AccessDenied") || name.contains("NotAuthorized") || name.contains("NotPermitted") -> "D-Bus 拒绝访问蓝牙"
            name.contains("ServiceUnknown") || name.contains("NameHasNoOwner") -> "BlueZ 服务不可用"
            else -> "BlueZ 不可用：${error.message ?: name}"
        }
        return TransportAvailability.Unavailable(reason)
    }
    override fun scan(): Flow<Advertisement> = channelFlow {
        val current = activeBus()
        scanLock.withLock {
            val adapter = current.objects().entries.firstOrNull { it.value[ADAPTER]?.get("Powered") == true }?.key
                ?: error("未找到已开启的蓝牙适配器")
            val devices = mutableMapOf<String, Map<String, Any?>>()
            val initialDevices = CompletableDeferred<Unit>()
            val events = launch(start = CoroutineStart.UNDISPATCHED) {
                current.signals { it.affects(scanInterfaces) }.collect { signal ->
                    if (signal is BluezSignal.Owner && !signal.present) throw IllegalStateException("BlueZ 服务已停止")
                    if (signal is BluezSignal.Changed && signal.path == adapter && signal.interfaceName == ADAPTER &&
                        signal.values["Powered"] == false
                    ) {
                        throw IllegalStateException("蓝牙适配器已关闭")
                    }
                    if (signal is BluezSignal.Removed && signal.path == adapter && ADAPTER in signal.interfaces) {
                        throw IllegalStateException("蓝牙适配器已移除")
                    }
                    initialDevices.await()
                    devices.updateAdvertisement(signal)?.let { send(it) }
                }
            }
            var started = false
            var failure: Throwable? = null
            try {
                current.startDiscovery(adapter)
                started = true
                for ((path, interfaces) in current.objects()) {
                    interfaces[DEVICE]?.let { devices[path] = it }
                }
                devices.values.mapNotNull { it.advertisement() }.forEach { send(it) }
                initialDevices.complete(Unit)
                awaitCancellation()
            } catch (error: Throwable) {
                failure = error
                if (error is org.freedesktop.dbus.errors.AccessDenied || error is org.bluez.Error.NotAuthorized) {
                    mutableAvailability.value = unavailable(error as Exception)
                }
                throw error
            } finally {
                events.cancel()
                if (started) {
                    withContext(NonCancellable) {
                        try {
                            current.stopDiscovery(adapter)
                        } catch (cleanup: Exception) {
                            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                        }
                    }
                }
            }
        }
    }
    override suspend fun bondedDevices(): List<DeviceCandidate> = audio.bondedDevices()
    override suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts = audio.connectionFacts(
        deviceId,
        classicAddress,
    )
    override val audioConnectedDevice: Flow<DeviceId?> = audio.audioConnectedDevice
    override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
        val current = activeBus()
        val ble = endpoint as? TransportEndpoint.BleGatt ?: throw IllegalArgumentException("Linux 不支持 SPP 控制连接")
        val address = normalizeBluetoothAddress(deviceId.value) ?: throw IllegalArgumentException("蓝牙地址无效")
        val path = current.objects().entries.firstOrNull { (_, interfaces) ->
            normalizeBluetoothAddress(interfaces[DEVICE]?.get("Address") as? String) == address
        }?.key
            ?: throw IllegalStateException("未找到蓝牙设备 $address")
        return GattControlConnection.open(BluezGattDriver(current, path), ble, onPhase)
    }
    suspend fun close() {
        job.cancelAndJoin()
        bus?.close()
        bus = null
        mutableAvailability.value = TransportAvailability.Unavailable("BlueZ 传输已关闭")
    }
    private inner class BluezAudioDriver : AudioFactsDriver {
        override suspend fun bondedDevices(): List<RawAudioDevice> = try {
            devices(activeBus().objects())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        override suspend fun snapshot(targetAddresses: Set<String>): RawAudioSnapshot {
            ready.await()
            val objects = try {
                bus?.objects().orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return RawAudioSnapshot(false, false, false, emptyList(), AudioProfile.entries.associateWith { null })
            }
            return audioSnapshot(objects, targetAddresses)
        }
        override val events: Flow<RawAudioEvent> = channelFlow {
            ready.await()
            val current = bus
            if (current == null) {
                send(RawAudioEvent.Ready)
                awaitCancellation()
            }
            val mutex = Mutex(locked = true)
            val previous = mutableMapOf<String, RawAudioDevice>()
            var powered = false
            var sleeping = false
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                current.signals(::affectsAudio).collect { signal ->
                    mutex.withLock {
                        if (signal is BluezSignal.PrepareForSleep) sleeping = signal.sleeping
                        if (sleeping || (signal is BluezSignal.Owner && !signal.present)) {
                            previous.clear()
                            powered = false
                            send(RawAudioEvent.AdapterOff)
                            return@withLock
                        }
                        val objects = try {
                            current.objects()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            return@withLock
                        }
                        val snapshot = audioSnapshot(objects, emptySet())
                        if (powered != snapshot.enabled) {
                            powered = snapshot.enabled
                            send(if (powered) RawAudioEvent.AdapterOn else RawAudioEvent.AdapterOff)
                        }
                        val now = snapshot.bondedDevices.associateBy { it.address }
                        for ((address, device) in previous) {
                            if (address !in now) {
                                for (profile in listOf(AudioProfile.A2DP, AudioProfile.HEADSET)) {
                                    send(RawAudioEvent.ProfileChanged(profile, device, false, false))
                                }
                            }
                        }
                        for (device in now.values) {
                            for (profile in listOf(AudioProfile.A2DP, AudioProfile.HEADSET)) {
                                val connected = device.address in snapshot.profiles[profile]?.connectedAddresses.orEmpty()
                                send(RawAudioEvent.ProfileChanged(profile, device, true, connected))
                            }
                        }
                        previous.clear()
                        previous.putAll(now)
                    }
                }
            }
            try {
                try {
                    val initial = current.objects()
                    powered = initial.values.any { it[ADAPTER]?.get("Powered") == true }
                    previous.putAll(devices(initial).associateBy { it.address })
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) { }
                send(RawAudioEvent.Ready)
            } finally {
                mutex.unlock()
            }
            collector.join()
        }
    }
}

private fun MutableMap<String, Map<String, Any?>>.updateAdvertisement(signal: BluezSignal): Advertisement? {
    val device = when (signal) {
        is BluezSignal.Added -> signal.interfaces[DEVICE]?.let { signal.path to it }

        is BluezSignal.Changed -> if (signal.interfaceName == DEVICE && signal.values.keys.any { it in advertisementKeys }) {
            signal.path to (get(signal.path).orEmpty() + signal.values)
        } else {
            null
        }

        is BluezSignal.Removed -> {
            if (DEVICE in signal.interfaces) remove(signal.path)
            null
        }

        is BluezSignal.Owner, is BluezSignal.PrepareForSleep -> null
    } ?: return null
    put(device.first, device.second)
    return device.second.advertisement()
}

private fun Map<String, Any?>.advertisement(): Advertisement? {
    val address = normalizeBluetoothAddress(this["Address"] as? String) ?: return null
    val manufacturer = (this["ManufacturerData"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
        val id = (key as? Number)?.toInt() ?: return@mapNotNull null
        val bytes = value.asBytes() ?: return@mapNotNull null
        id to bytes
    }?.toMap().orEmpty()
    return Advertisement(
        DeviceId(address),
        this["Name"] as? String ?: this["Alias"] as? String,
        (this["RSSI"] as? Number)?.toInt(),
        manufacturer,
    )
}
private fun devices(objects: BluezObjects): List<RawAudioDevice> = objects.values.mapNotNull { interfaces ->
    val properties = interfaces[DEVICE] ?: return@mapNotNull null
    if (properties["Paired"] != true) return@mapNotNull null
    val address = normalizeBluetoothAddress(properties["Address"] as? String) ?: return@mapNotNull null
    RawAudioDevice(properties["Name"] as? String ?: properties["Alias"] as? String, address)
}
private fun audioSnapshot(objects: BluezObjects, targets: Set<String>): RawAudioSnapshot {
    val paired = devices(objects)
    val addresses = objects.mapNotNull { (path, interfaces) ->
        val address = normalizeBluetoothAddress(interfaces[DEVICE]?.get("Address") as? String) ?: return@mapNotNull null
        path to address
    }.toMap()
    val connected = AudioProfile.entries.associateWith { mutableSetOf<String>() }
    for ((path, interfaces) in objects) {
        val transport = interfaces[MEDIA_TRANSPORT] ?: continue
        val owner = (transport["Device"] as? String) ?: addresses.keys.firstOrNull { path == it || path.startsWith("$it/") } ?: continue
        val address = addresses[owner] ?: continue
        if (objects[owner.substringBeforeLast('/')]?.get(ADAPTER)?.get("Powered") == false) continue
        val profile = profileForUuid((transport["UUID"] as? String)?.lowercase()) ?: continue
        val advertised = objects[owner]?.get(DEVICE)?.get("UUIDs") as? Collection<*>
        if (advertised != null && advertised.none { profileForUuid((it as? String)?.lowercase()) == profile }) continue
        connected.getValue(profile).add(address)
    }
    val profiles = connected.mapValues { (_, list) ->
        RawProfileSnapshot(
            list.toList(),
            (targets + addresses.values).associateWith {
                if (it in list) AudioConnectionState.CONNECTED else AudioConnectionState.DISCONNECTED
            },
        )
    }
    return RawAudioSnapshot(
        objects.values.any { it[ADAPTER]?.get("Powered") == true },
        false,
        connected.getValue(AudioProfile.LE_AUDIO).isNotEmpty(),
        paired,
        profiles,
    )
}

private fun profileForUuid(uuid: String?): AudioProfile? = when (uuid) {
    "0000110a-0000-1000-8000-00805f9b34fb", "0000110b-0000-1000-8000-00805f9b34fb" -> AudioProfile.A2DP

    "0000184e-0000-1000-8000-00805f9b34fb",
    "0000184f-0000-1000-8000-00805f9b34fb",
    "00001850-0000-1000-8000-00805f9b34fb",
    -> AudioProfile.LE_AUDIO

    else -> null
}

private val audioDeviceProperties = setOf("Address", "Name", "Alias", "Paired", "Connected", "UUIDs")
private val scanInterfaces = setOf(ADAPTER, DEVICE)
private val advertisementKeys = setOf("Address", "Name", "Alias", "RSSI", "ManufacturerData")

private fun affectsAudio(signal: BluezSignal): Boolean = when {
    signal is BluezSignal.Changed && signal.interfaceName == DEVICE -> signal.values.keys.any { it in audioDeviceProperties }
    else -> signal.affects(audioInterfaces)
}

private val availabilityInterfaces = setOf(ADAPTER)
private val audioInterfaces = setOf(ADAPTER, DEVICE, MEDIA_TRANSPORT)

private fun BluezSignal.affects(interfaces: Set<String>): Boolean = when (this) {
    is BluezSignal.PrepareForSleep -> MEDIA_TRANSPORT in interfaces
    is BluezSignal.Owner -> true
    is BluezSignal.Changed -> interfaceName in interfaces
    is BluezSignal.Added -> this.interfaces.keys.any { it in interfaces }
    is BluezSignal.Removed -> this.interfaces.any { it in interfaces }
}

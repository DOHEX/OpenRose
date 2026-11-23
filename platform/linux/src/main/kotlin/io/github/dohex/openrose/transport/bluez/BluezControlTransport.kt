package io.github.dohex.openrose.transport.bluez

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.freedesktop.dbus.exceptions.DBusExecutionException

class BluezControlTransport private constructor(
    scope: CoroutineScope,
    private val openBus: suspend () -> BluezBus,
) : DesktopControlTransport {
    constructor(scope: CoroutineScope) : this(scope, { NativeBluezBus.open() })
    internal constructor(scope: CoroutineScope, bus: BluezBus) : this(scope, { bus })

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val mutableAvailability = MutableStateFlow<TransportAvailability>(TransportAvailability.Unavailable("正在初始化 BlueZ"))
    override val availability: StateFlow<TransportAvailability> = mutableAvailability.asStateFlow()

    @Volatile private var bus: BluezBus? = null

    @Volatile private var scanAdapter: String? = null
    private val scanLock = Mutex()
    private val releasedLinks = ReleasedBluezLinks()
    val audioProfiles = BluezAudioProfiles(releasedLinks, ::activeBus)
    private val audio = AudioFacts(BluezAudioDriver())
    override val supportsInitializationRecovery = true

    init {
        work.launch {
            try {
                val current = openBus()
                bus = current
                launch(start = CoroutineStart.UNDISPATCHED) { releasedLinks.observe(current) }
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    current.signals { it.affects(availabilityInterfaces) }.collect { updateAvailability(current) }
                }
                updateAvailability(current)
                ready.complete(Unit)
                observer.join()
            } catch (error: LinkageError) {
                mutableAvailability.value = TransportAvailability.Unavailable(
                    "BlueZ 运行环境缺少依赖：${error.message ?: error.javaClass.simpleName}",
                )
            } catch (error: DBusExecutionException) {
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
        val current = checkNotNull(bus) { (availability.value as? TransportAvailability.Unavailable)?.reason ?: "BlueZ 不可用" }
        return when (val status = availability.value) {
            TransportAvailability.Available -> current
            TransportAvailability.Disabled -> error("蓝牙适配器已关闭")
            is TransportAvailability.Unavailable -> error(status.reason)
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
        } catch (error: DBusExecutionException) {
            unavailable(error)
        }
    }
    private fun unavailable(error: Throwable): TransportAvailability.Unavailable {
        val name = error.javaClass.simpleName
        val reason = when {
            error.isBluezAccessDenied -> "D-Bus 拒绝访问蓝牙"
            name.contains("ServiceUnknown") || name.contains("NameHasNoOwner") -> "BlueZ 服务不可用"
            else -> "BlueZ 不可用：${error.message ?: name}"
        }
        return TransportAvailability.Unavailable(reason)
    }
    override fun scan(): Flow<Advertisement> = channelFlow {
        val current = activeBus()
        scanLock.withLock {
            val adapter = current.objects().filterValues { it[ADAPTER]?.get("Powered") == true }.keys.minOrNull()
                ?: error("未找到已开启的蓝牙适配器")
            val devices = mutableMapOf<String, Map<String, Any?>>()
            val initialDevices = CompletableDeferred<Unit>()
            val events = launch(start = CoroutineStart.UNDISPATCHED) {
                current.signals { it.affects(scanInterfaces) }.collect { signal ->
                    signal.discoveryFailure(adapter)?.let { error(it) }
                    initialDevices.await()
                    devices.updateAdvertisement(signal, adapter)?.let { send(it) }
                }
            }
            current.startDiscovery(adapter)
            scanAdapter = adapter
            @Suppress("TooGenericExceptionCaught")
            try {
                for ((path, interfaces) in current.objects()) {
                    if (path.substringBeforeLast('/') == adapter) interfaces[DEVICE]?.let { devices[path] = it }
                }
                devices.values.mapNotNull { it.advertisement() }.forEach { send(it) }
                initialDevices.complete(Unit)
                awaitCancellation()
            } catch (end: Throwable) {
                events.cancel()
                withContext(NonCancellable) {
                    try {
                        current.stopDiscovery(adapter)
                    } catch (cleanup: Exception) {
                        end.addSuppressed(cleanup)
                    }
                }
                throw end
            }
        }
    }.catch { error ->
        if (error.isBluezAccessDenied) mutableAvailability.value = unavailable(error)
        throw error
    }

    override suspend fun bondedDevices(): List<DeviceCandidate> = audio.bondedDevices().distinctBy { it.deviceId }
    override suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts = audio.connectionFacts(
        deviceId,
        classicAddress,
    )
    override val audioConnectedDevice: Flow<DeviceId?> = audio.audioConnectedDevice
    override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
        val current = activeBus()
        require(endpoint is TransportEndpoint.BleGatt) { "Linux 不支持 SPP 控制连接" }
        val address = requireNotNull(normalizeBluetoothAddress(deviceId.value)) { "蓝牙地址无效" }
        val path = checkNotNull(current.objects().devicePath(address, scanAdapter = scanAdapter)) { "未找到蓝牙设备 $address" }
        releasedLinks.resumeLocal(path)
        return GattControlConnection.open(BluezGattDriver(current, path), endpoint, onPhase)
    }
    override suspend fun close() {
        job.cancelAndJoin()
        bus?.close()
        bus = null
        mutableAvailability.value = TransportAvailability.Unavailable("BlueZ 传输已关闭")
    }
    private inner class BluezAudioDriver : AudioFactsDriver {
        override suspend fun bondedDevices(): List<RawAudioDevice> {
            ready.await()
            return devices(checkNotNull(bus) { "BlueZ 不可用" }.objects())
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

private fun BluezSignal.discoveryFailure(adapter: String): String? = when (this) {
    is BluezSignal.Owner -> if (!present) "BlueZ 服务已停止" else null
    is BluezSignal.Changed -> if (path == adapter && interfaceName == ADAPTER && values["Powered"] == false) "蓝牙适配器已关闭" else null
    is BluezSignal.Removed -> if (path == adapter && ADAPTER in interfaces) "蓝牙适配器已移除" else null
    is BluezSignal.Added, is BluezSignal.PrepareForSleep -> null
}

private fun MutableMap<String, Map<String, Any?>>.updateAdvertisement(signal: BluezSignal, adapter: String): Advertisement? {
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
    if (device.first.substringBeforeLast('/') != adapter) return null
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
private fun devices(objects: BluezObjects): List<RawAudioDevice> = objects.mapNotNull { (path, interfaces) ->
    val properties = interfaces[DEVICE] ?: return@mapNotNull null
    if (properties["Paired"] != true || !objects.onPoweredAdapter(path)) return@mapNotNull null
    val address = normalizeBluetoothAddress(properties["Address"] as? String) ?: return@mapNotNull null
    RawAudioDevice(properties["Name"] as? String ?: properties["Alias"] as? String, address)
}
internal fun BluezObjects.hasMedia(address: String): Boolean {
    val a2dp = audioSnapshot(this, setOf(address)).profiles[AudioProfile.A2DP]
    return address in a2dp?.connectedAddresses.orEmpty()
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
        objects.connectedAudioProfile(path, transport, addresses)?.let { (profile, address) ->
            connected.getValue(profile).add(address)
        }
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

private fun BluezObjects.connectedAudioProfile(
    path: String,
    transport: Map<String, Any?>,
    addresses: Map<String, String>,
): Pair<AudioProfile, String>? {
    val owner = (transport["Device"] as? String) ?: addresses.keys.firstOrNull { path == it || path.startsWith("$it/") } ?: return null
    val address = addresses[owner] ?: return null
    if (this[owner.substringBeforeLast('/')]?.get(ADAPTER)?.get("Powered") == false) return null
    val profile = profileForUuid(transport["UUID"]) ?: return null
    val advertised = this[owner]?.get(DEVICE)?.get("UUIDs") as? Collection<*>
    return if (advertised == null || advertised.any { profileForUuid(it) == profile }) profile to address else null
}

private fun profileForUuid(uuid: Any?): AudioProfile? = when ((uuid as? String)?.lowercase()) {
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

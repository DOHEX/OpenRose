package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.AudioConnectionState
import io.github.dohex.openrose.core.transport.AudioFactsDriver
import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.GattCharacteristic
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.core.transport.GattEvent
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattService
import io.github.dohex.openrose.core.transport.GattSubscription
import io.github.dohex.openrose.core.transport.RawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent
import io.github.dohex.openrose.core.transport.RawAudioSnapshot
import io.github.dohex.openrose.core.transport.RawProfileSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.SimulatedHeadset
import uniffi.openrose_ffi.modelDisplayName
import uniffi.openrose_ffi.modelGattProfile
import uniffi.openrose_headset.FirmwareVersion
import uniffi.openrose_headset.LocalDateTime
import uniffi.openrose_headset.ModelProfile
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SimulatedGatt internal constructor(profile: ModelProfile, private val device: SimulatedHeadset) : GattDriver {
    private val gatt = modelGattProfile(profile)
    private val incoming = Channel<GattEvent>(Channel.UNLIMITED)
    val writer = GattCharacteristic(gatt.writeUuid, canWrite = true, canWriteWithoutResponse = true)
    val notifier = GattCharacteristic(gatt.notifyUuid, canNotify = true)
    val writes = CopyOnWriteArrayList<String>()
    val closes = CopyOnWriteArrayList<String>()

    @Volatile var connectFailure: GattException? = null

    @Volatile var muted = false

    override val events: Flow<GattEvent> = incoming.receiveAsFlow()

    override suspend fun connect() {
        connectFailure?.let { throw it }
    }

    override suspend fun discoverServices(): List<GattService> = listOf(GattService(gatt.serviceUuid, listOf(writer, notifier)))

    override suspend fun requestMtu(preferred: Int): Int = 185

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) {
        check(characteristic === notifier)
    }

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) {
        check(characteristic === notifier)
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
        check(characteristic === writer)
        writes.add(value.toLowerHex())
        if (muted) return
        for (reply in device.receive(value)) incoming.trySend(GattEvent.Notification(notifier, reply))
    }

    override suspend fun close() {
        closes.add("closed")
        incoming.close()
    }

    fun disconnect(failure: GattException) {
        incoming.trySend(GattEvent.Disconnected(failure))
    }

    fun servicesChanged() {
        incoming.trySend(GattEvent.ServicesChanged)
    }

    fun inject(hex: String) {
        incoming.trySend(GattEvent.Notification(notifier, hex.hexToBytes()))
    }
}

class SimulatedSppLink : SppLink {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: Flow<ByteArray> = incoming.receiveAsFlow()
    override val maxWriteSize = 260
    val written = CopyOnWriteArrayList<List<Byte>>()
    var openFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var subscribed = false
    var opened = false
    var closed = false

    override suspend fun open(onPhase: (ConnectionPhase) -> Unit) {
        openFailure?.let { throw it }
        opened = true
    }

    override suspend fun subscribe() {
        subscribed = true
    }

    override suspend fun write(bytes: ByteArray) {
        writeFailure?.let { throw it }
        written.add(bytes.toList())
    }

    override suspend fun close() {
        closeFailure?.let { throw it }
        closed = true
        incoming.close(IOException("SPP 连接已关闭"))
    }

    fun deliver(vararg bytes: Byte) {
        incoming.trySend(bytes)
    }

    fun breakLink(error: Throwable) {
        incoming.close(error)
    }
}

class SimulatedDrivers(
    val profile: ModelProfile = ModelProfile.I5_ZT,
    val address: String = DEFAULT_ADDRESS,
    private val alias: String? = null,
) : KotlinCoreDrivers,
    AutoCloseable {
    val device = SimulatedHeadset(profile, FirmwareVersion(listOf(1, 2, 5), "125"))
    val gatts = CopyOnWriteArrayList<SimulatedGatt>()
    val gattRequests = CopyOnWriteArrayList<String>()
    val registered = AtomicBoolean(false)
    val audioEvents = Channel<RawAudioEvent>(Channel.UNLIMITED)
    val scans = Channel<Advertisement>(Channel.UNLIMITED)
    val scanStops = AtomicBoolean(false)
    var scanFailure: Throwable? = null

    @Volatile var connectFailure: GattException? = null

    @Volatile var audioConnected = true

    @Volatile var muted = false
    var gattFailure: Throwable? = null
    var sppFailure: Throwable? = null
    var sppLink: SimulatedSppLink? = null
    var sppRequest: Pair<String, String>? = null
    override var supportsInitializationRecovery = false
    val pairedDevices = CopyOnWriteArrayList<RawAudioDevice>().apply { add(RawAudioDevice(modelDisplayName(profile), address, alias)) }

    override fun gatt(address: String): GattDriver {
        gattRequests.add(address)
        gattFailure?.let { throw it }
        return SimulatedGatt(profile, device).also {
            it.connectFailure = connectFailure
            it.muted = muted
            gatts.add(it)
        }
    }

    override fun scan(): Flow<Advertisement> = callbackFlow {
        scanFailure?.let { close(it) }
        val job = launch { for (advertisement in scans) trySend(advertisement) }
        awaitClose {
            scanStops.set(true)
            job.cancel()
        }
    }

    override fun spp(address: String, serviceUuid: String): SppLink {
        sppRequest = address to serviceUuid
        sppFailure?.let { throw it }
        return SimulatedSppLink().also { sppLink = it }
    }

    override val audio: AudioFactsDriver = object : AudioFactsDriver {
        override val events: Flow<RawAudioEvent> = callbackFlow {
            registered.set(true)
            trySend(RawAudioEvent.Ready)
            val job = launch { for (event in audioEvents) trySend(event) }
            awaitClose { job.cancel() }
        }

        override suspend fun bondedDevices(): List<RawAudioDevice> = pairedDevices.toList()

        override suspend fun snapshot(targetAddresses: Set<String>): RawAudioSnapshot {
            val state = if (audioConnected) AudioConnectionState.CONNECTED else AudioConnectionState.DISCONNECTED
            return RawAudioSnapshot(
                enabled = true,
                sppAvailable = false,
                leAudioObservable = false,
                bondedDevices = pairedDevices.toList(),
                profiles = mapOf(
                    AudioProfile.A2DP to RawProfileSnapshot(
                        if (audioConnected) listOf(address) else emptyList(),
                        mapOf(address to state),
                    ),
                ),
            )
        }
    }

    fun fastTiming() = SessionTiming(
        connectTimeout = Duration.ofMillis(2_000),
        queryTimeout = Duration.ofMillis(500),
        writeInterval = Duration.ZERO,
        writeTimeout = Duration.ofMillis(500),
    )

    fun fixedClock() = object : LocalClock {
        override fun now() = LocalDateTime(2026u, 11u, 26u, 10u, 0u, 0u)
    }

    override fun close() {
        scans.close()
        audioEvents.close()
        device.close()
    }

    companion object {
        const val DEFAULT_ADDRESS = "AA:BB:CC:00:00:05"
    }
}

internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

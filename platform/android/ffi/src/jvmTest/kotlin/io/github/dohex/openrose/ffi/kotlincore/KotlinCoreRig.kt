package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ConnectionPhase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PermissionState
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_control.TransportAvailability
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
import io.github.dohex.openrose.core.transport.Advertisement as KotlinAdvertisement
import io.github.dohex.openrose.core.transport.AudioConnectionState as KotlinAudioConnectionState
import io.github.dohex.openrose.core.transport.AudioFactsDriver as KotlinAudioFactsDriver
import io.github.dohex.openrose.core.transport.AudioProfile as KotlinAudioProfile
import io.github.dohex.openrose.core.transport.GattCharacteristic as KotlinGattCharacteristic
import io.github.dohex.openrose.core.transport.GattDriver as KotlinGattDriver
import io.github.dohex.openrose.core.transport.GattEvent as KotlinGattEvent
import io.github.dohex.openrose.core.transport.GattException as KotlinGattException
import io.github.dohex.openrose.core.transport.GattService as KotlinGattService
import io.github.dohex.openrose.core.transport.GattSubscription as KotlinGattSubscription
import io.github.dohex.openrose.core.transport.RawAudioDevice as KotlinRawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent as KotlinRawAudioEvent
import io.github.dohex.openrose.core.transport.RawAudioSnapshot as KotlinRawAudioSnapshot
import io.github.dohex.openrose.core.transport.RawProfileSnapshot as KotlinRawProfileSnapshot

internal const val ADDRESS = "AA:BB:CC:00:00:05"

internal class SimulatedGatt(profile: ModelProfile, private val device: SimulatedHeadset) : KotlinGattDriver {
    private val gatt = modelGattProfile(profile)
    private val incoming = Channel<KotlinGattEvent>(Channel.UNLIMITED)
    val writer = KotlinGattCharacteristic(gatt.writeUuid, canWrite = true, canWriteWithoutResponse = true)
    val notifier = KotlinGattCharacteristic(gatt.notifyUuid, canNotify = true)
    val writes = CopyOnWriteArrayList<String>()
    val closes = CopyOnWriteArrayList<String>()

    @Volatile var connectFailure: KotlinGattException? = null

    @Volatile var muted = false

    override val events: Flow<KotlinGattEvent> = incoming.receiveAsFlow()

    override suspend fun connect() {
        connectFailure?.let { throw it }
    }

    override suspend fun discoverServices(): List<KotlinGattService> = listOf(
        KotlinGattService(gatt.serviceUuid, listOf(writer, notifier)),
    )

    override suspend fun requestMtu(preferred: Int): Int = 185

    override suspend fun setNotificationRouting(characteristic: KotlinGattCharacteristic, enabled: Boolean) {
        check(characteristic === notifier)
    }

    override suspend fun configureSubscription(characteristic: KotlinGattCharacteristic, subscription: KotlinGattSubscription) {
        check(characteristic === notifier)
    }

    override suspend fun write(characteristic: KotlinGattCharacteristic, value: ByteArray, withResponse: Boolean) {
        check(characteristic === writer)
        writes.add(value.toLowerHex())
        if (muted) return
        for (reply in device.receive(value)) incoming.trySend(KotlinGattEvent.Notification(notifier, reply))
    }

    override suspend fun close() {
        closes.add("closed")
        incoming.close()
    }

    fun disconnect(failure: KotlinGattException) {
        incoming.trySend(KotlinGattEvent.Disconnected(failure))
    }

    fun servicesChanged() {
        incoming.trySend(KotlinGattEvent.ServicesChanged)
    }

    fun inject(hex: String) {
        incoming.trySend(KotlinGattEvent.Notification(notifier, hex.hexToBytes()))
    }
}

internal class Rig(val profile: ModelProfile = ModelProfile.I5_ZT) :
    KotlinCoreDrivers,
    AutoCloseable {
    val device = SimulatedHeadset(profile, FirmwareVersion(listOf(1, 2, 5), "125"))
    val gatts = CopyOnWriteArrayList<SimulatedGatt>()
    val gattRequests = CopyOnWriteArrayList<String>()
    val registered = AtomicBoolean(false)
    val audioEvents = Channel<KotlinRawAudioEvent>(Channel.UNLIMITED)
    val scans = Channel<KotlinAdvertisement>(Channel.UNLIMITED)
    val scanStops = AtomicBoolean(false)
    var scanFailure: Throwable? = null

    @Volatile var connectFailure: KotlinGattException? = null

    @Volatile var audioConnected = true

    @Volatile var muted = false
    var gattFailure: Throwable? = null
    var sppFailure: Throwable? = null
    var sppLink: FakeSppLink? = null
    var sppRequest: Pair<String, String>? = null
    override var supportsInitializationRecovery = false

    override fun gatt(address: String): KotlinGattDriver {
        gattRequests.add(address)
        gattFailure?.let { throw it }
        return SimulatedGatt(profile, device).also {
            it.connectFailure = connectFailure
            it.muted = muted
            gatts.add(it)
        }
    }

    override fun scan(): Flow<KotlinAdvertisement> = callbackFlow {
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
        return FakeSppLink().also { sppLink = it }
    }

    override val audio: KotlinAudioFactsDriver = object : KotlinAudioFactsDriver {
        override val events: Flow<KotlinRawAudioEvent> = callbackFlow {
            registered.set(true)
            trySend(KotlinRawAudioEvent.Ready)
            val job = launch { for (event in audioEvents) trySend(event) }
            awaitClose { job.cancel() }
        }

        override suspend fun bondedDevices(): List<KotlinRawAudioDevice> = pairedDevices.toList()

        override suspend fun snapshot(targetAddresses: Set<String>): KotlinRawAudioSnapshot = KotlinRawAudioSnapshot(
            enabled = true,
            sppAvailable = false,
            leAudioObservable = false,
            bondedDevices = pairedDevices.toList(),
            profiles = mapOf(
                KotlinAudioProfile.A2DP to KotlinRawProfileSnapshot(
                    if (audioConnected) listOf(ADDRESS) else emptyList(),
                    mapOf(ADDRESS to if (audioConnected) KotlinAudioConnectionState.CONNECTED else KotlinAudioConnectionState.DISCONNECTED),
                ),
            ),
        )
    }

    private fun paired() = KotlinRawAudioDevice(modelDisplayName(profile), ADDRESS, alias = "My i5")

    val pairedDevices = CopyOnWriteArrayList<KotlinRawAudioDevice>().apply { add(paired()) }

    fun platformState(): PlatformState = PlatformState(
        permission = PermissionState.GRANTED,
        scanPermission = PermissionState.GRANTED,
        transport = TransportAvailability.Available,
        hostPlatform = HostPlatform.ANDROID,
        controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
        userUnlocked = true,
    )

    fun timing() = SessionTiming(
        connectTimeout = Duration.ofMillis(2_000),
        queryTimeout = Duration.ofMillis(500),
        writeInterval = Duration.ZERO,
        writeTimeout = Duration.ofMillis(500),
    )

    fun clock() = object : LocalClock {
        override fun now() = LocalDateTime(2026u, 11u, 26u, 10u, 0u, 0u)
    }

    override fun close() {
        scans.close()
        audioEvents.close()
        device.close()
    }
}

internal class FakeSppLink : SppLink {
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

internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

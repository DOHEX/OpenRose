package io.github.dohex.openrose.module.nativecore

import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.platform.AndroidAudioProfiles
import io.github.dohex.openrose.platform.AndroidGattDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import uniffi.openrose_control.AudioConnectionState
import uniffi.openrose_control.AudioProfile
import uniffi.openrose_control.GattCharacteristic
import uniffi.openrose_control.GattError
import uniffi.openrose_control.GattEvent
import uniffi.openrose_control.GattFailureKind
import uniffi.openrose_control.GattService
import uniffi.openrose_control.GattSubscription
import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PermissionState
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.RawAudioDevice
import uniffi.openrose_control.RawAudioEvent
import uniffi.openrose_control.RawAudioSnapshot
import uniffi.openrose_control.RawProfileSnapshot
import uniffi.openrose_control.TransportAvailability
import uniffi.openrose_control.TransportUnavailableCode
import uniffi.openrose_ffi.AudioEvents
import uniffi.openrose_ffi.AudioFactsDriver
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.GattDriver
import uniffi.openrose_ffi.GattDriverException
import uniffi.openrose_ffi.ScanDriver
import uniffi.openrose_ffi.SppDriver
import uniffi.openrose_ffi.TransportDriverException
import uniffi.openrose_headset.HeadsetId
import java.util.IdentityHashMap
import io.github.dohex.openrose.core.PlatformState as CorePlatformState
import io.github.dohex.openrose.core.TransportAvailability as CoreTransportAvailability
import io.github.dohex.openrose.core.transport.GattCharacteristic as CoreGattCharacteristic
import io.github.dohex.openrose.core.transport.GattEvent as CoreGattEvent
import io.github.dohex.openrose.core.transport.GattService as CoreGattService
import io.github.dohex.openrose.core.transport.RawAudioDevice as CoreRawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent as CoreRawAudioEvent
import io.github.dohex.openrose.core.transport.RawAudioSnapshot as CoreRawAudioSnapshot
import io.github.dohex.openrose.core.transport.RawProfileSnapshot as CoreRawProfileSnapshot

internal class RustAndroidTransport(private val context: Context) :
    ControlTransport,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = RustAudioFacts(AndroidAudioProfiles(context), scope)

    override fun openGatt(headset: HeadsetId): GattDriver {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportDriverException.Terminal("系统蓝牙适配器不可用")
        val device = try {
            adapter.getRemoteDevice(headset)
        } catch (_: IllegalArgumentException) {
            throw TransportDriverException.InvalidArgument("耳机地址无效")
        }
        return RustGattDriver(AndroidGattDriver(context, device), scope)
    }

    override fun startScan(): ScanDriver = unsupported("扫描")

    override fun openSpp(headset: HeadsetId, address: String, serviceUuid: String): SppDriver = unsupported("SPP")

    override fun audioFacts(): AudioFactsDriver = audio

    override fun supportsInitializationRecovery(): Boolean = true

    override fun close() {
        scope.cancel()
    }

    private fun unsupported(feature: String): Nothing = throw TransportDriverException.Unsupported("系统模块试验不提供$feature")
}

internal fun CorePlatformState.toRust(): PlatformState = PlatformState(
    permission = permission.named(),
    transport = when (val availability = transport) {
        CoreTransportAvailability.Available -> TransportAvailability.Available

        CoreTransportAvailability.Disabled -> TransportAvailability.Disabled

        is CoreTransportAvailability.Unavailable -> TransportAvailability.Unavailable(
            availability.code.named<TransportUnavailableCode>(),
            availability.detail,
        )
    },
    hostPlatform = hostPlatform.named<HostPlatform>(),
    userUnlocked = userUnlocked,
    scanPermission = scanPermission.named<PermissionState>(),
    controlTransportApplicability = controlTransportApplicability.named<PlatformApplicability>(),
)

private inline fun <reified T : Enum<T>> Enum<*>.named(): T = enumValueOf<T>(name)

private class CharacteristicTable(
    val byId: Map<ULong, CoreGattCharacteristic>,
    val ids: IdentityHashMap<CoreGattCharacteristic, ULong>,
)

@Suppress("TooManyFunctions")
private class RustGattDriver(private val driver: AndroidGattDriver, scope: CoroutineScope) : GattDriver {
    private val incoming = Channel<GattEvent>(Channel.UNLIMITED)

    @Volatile private var table = CharacteristicTable(emptyMap(), IdentityHashMap())

    init {
        scope.launch { pumpEvents() }
    }

    override suspend fun nextEvent(): GattEvent? = incoming.receiveCatching().getOrNull()

    override suspend fun connect() = guarded { driver.connect() }

    override suspend fun discoverServices(): List<GattService> = publish(guarded { driver.discoverServices() })

    override suspend fun requestMtu(preferred: UShort): UShort? = guarded { driver.requestMtu(preferred.toInt()) }?.toUShort()

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) = guarded {
        driver.setNotificationRouting(resolve(characteristic), enabled)
    }

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) = guarded {
        driver.configureSubscription(resolve(characteristic), subscription.named())
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) = guarded {
        driver.write(resolve(characteristic), value, withResponse)
    }

    override suspend fun closeLink() = guarded { driver.close() }

    private suspend fun pumpEvents() {
        try {
            driver.events.collect { event -> event.toRust(table)?.let { incoming.send(it) } }
        } catch (failure: GattException) {
            incoming.send(GattEvent.Disconnected(failure.toGattError()))
        } finally {
            incoming.close()
        }
    }

    private fun resolve(characteristic: GattCharacteristic): CoreGattCharacteristic = table.byId[characteristic.instanceId]
        ?: throw GattDriverException.Failed(GattFailureKind.UNSUPPORTED, "蓝牙特征不可用")

    private fun publish(services: List<CoreGattService>): List<GattService> {
        val byId = LinkedHashMap<ULong, CoreGattCharacteristic>()
        val ids = IdentityHashMap<CoreGattCharacteristic, ULong>()
        val published = services.map { service ->
            GattService(
                service.uuid,
                service.characteristics.map { characteristic ->
                    val id = ids[characteristic] ?: (ids.size + 1).toULong().also {
                        ids[characteristic] = it
                        byId[it] = characteristic
                    }
                    GattCharacteristic(
                        id,
                        characteristic.uuid,
                        characteristic.canWrite,
                        characteristic.canWriteWithoutResponse,
                        characteristic.canNotify,
                        characteristic.canIndicate,
                    )
                },
            )
        }
        table = CharacteristicTable(byId, ids)
        return published
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: GattException) {
        throw GattDriverException.Failed(failure.kind.named<GattFailureKind>(), failure.message.orEmpty()).also { it.initCause(failure) }
    }
}

private fun CoreGattEvent.toRust(table: CharacteristicTable): GattEvent? = when (this) {
    is CoreGattEvent.Notification -> table.ids[characteristic]?.let { GattEvent.Notification(it, value) }
    CoreGattEvent.ServicesChanged -> GattEvent.ServicesChanged
    is CoreGattEvent.Disconnected -> GattEvent.Disconnected(failure.toGattError())
}

private fun GattException.toGattError(): GattError = GattError(kind.named<GattFailureKind>(), message.orEmpty())

private class RustAudioFacts(private val source: AndroidAudioProfiles, private val scope: CoroutineScope) : AudioFactsDriver {
    override fun subscribe(): AudioEvents {
        val events = source.events.mapNotNull { it.toRust() }.produceIn(scope)
        return object : AudioEvents {
            override suspend fun nextEvent(): RawAudioEvent? = events.receiveCatching().getOrNull()
        }
    }

    override suspend fun bondedDevices(): List<RawAudioDevice> = source.bondedDevices().map { it.toRust() }

    override suspend fun snapshot(targetAddresses: List<String>): RawAudioSnapshot = source.snapshot(targetAddresses.toSet()).toRust()
}

private fun CoreRawAudioEvent.toRust(): RawAudioEvent? = when (this) {
    CoreRawAudioEvent.AdapterOff -> RawAudioEvent.AdapterOff
    CoreRawAudioEvent.AdapterOn -> RawAudioEvent.AdapterOn
    CoreRawAudioEvent.Ready -> null
    CoreRawAudioEvent.BondedDevicesChanged -> RawAudioEvent.BondedDevicesChanged
    is CoreRawAudioEvent.ProfileChanged -> RawAudioEvent.ProfileChanged(profile.named<AudioProfile>(), device.toRust(), bonded, connected)
}

private fun CoreRawAudioDevice.toRust(): RawAudioDevice = RawAudioDevice(name, address, alias)

private fun CoreRawProfileSnapshot.toRust(): RawProfileSnapshot = RawProfileSnapshot(
    connectedAddresses,
    states.mapValues { (_, state) -> state.named<AudioConnectionState>() },
)

private fun CoreRawAudioSnapshot.toRust(): RawAudioSnapshot = RawAudioSnapshot(
    enabled,
    sppAvailable,
    leAudioObservable,
    bondedDevices.map { it.toRust() },
    profiles.entries.associate { (profile, snapshot) -> profile.named<AudioProfile>() to snapshot?.toRust() },
)

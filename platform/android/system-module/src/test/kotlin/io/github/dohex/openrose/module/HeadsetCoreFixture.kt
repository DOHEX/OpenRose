package io.github.dohex.openrose.module

import android.os.Binder
import android.os.Bundle
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.AudioConnectionState
import io.github.dohex.openrose.core.transport.AudioFactsDriver
import io.github.dohex.openrose.core.transport.AudioProfile
import io.github.dohex.openrose.core.transport.GattCharacteristic
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.core.transport.GattEvent
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.core.transport.GattService
import io.github.dohex.openrose.core.transport.GattSubscription
import io.github.dohex.openrose.core.transport.RawAudioDevice
import io.github.dohex.openrose.core.transport.RawAudioEvent
import io.github.dohex.openrose.core.transport.RawAudioSnapshot
import io.github.dohex.openrose.core.transport.RawProfileSnapshot
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import io.github.dohex.openrose.ffi.drivers.SppLink
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.RuntimeEnvironment
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.modelDisplayName
import uniffi.openrose_ffi.modelGattProfile
import uniffi.openrose_headset.LocalDateTime
import uniffi.openrose_headset.ModelProfile
import java.time.Duration
import kotlin.test.assertNotNull
import kotlin.test.fail

internal fun coreRuntime(
    scope: CoroutineScope,
    settings: UserSettings,
    platform: PlatformState,
    sessionIdBase: Long,
    drivers: PlatformDrivers = TestHeadsetDrivers(connected = false),
): CoreControlRuntime = CoreControlRuntime(
    transport = ControlTransportAdapter(drivers),
    initialPlatformState = platform,
    initialSettings = settings,
    scope = scope,
    timing = SessionTiming(
        connectTimeout = Duration.ofMillis(2_000),
        queryTimeout = Duration.ofMillis(500),
        writeInterval = Duration.ZERO,
        writeTimeout = Duration.ofMillis(500),
    ),
    sessionIdBase = sessionIdBase,
    clock = object : LocalClock {
        override fun now() = LocalDateTime(2026u, 11u, 26u, 10u, 0u, 0u)
    },
)

internal suspend fun awaitCondition(description: String, check: () -> Boolean) {
    withTimeoutOrNull(3_000) {
        while (!check()) delay(5)
    } ?: fail("timed out waiting for $description")
}

internal class TestHeadsetDrivers(private val connected: Boolean) : PlatformDrivers {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val gattProfile = modelGattProfile(ModelProfile.I5_ZT)
    private val writer = GattCharacteristic(gattProfile.writeUuid, canWrite = true, canWriteWithoutResponse = true)
    private val notifier = GattCharacteristic(gattProfile.notifyUuid, canNotify = true)
    private val incoming = Channel<GattEvent>(Channel.UNLIMITED)

    override val supportsInitializationRecovery = true
    override val audio = object : AudioFactsDriver {
        override val events = callbackFlow {
            trySend(RawAudioEvent.Ready)
            awaitClose {}
        }

        override suspend fun bondedDevices() = if (connected) listOf(RawAudioDevice(modelDisplayName(ModelProfile.I5_ZT), address, null)) else emptyList()

        override suspend fun snapshot(targetAddresses: Set<String>) = RawAudioSnapshot(
            enabled = true,
            sppAvailable = false,
            leAudioObservable = false,
            bondedDevices = bondedDevices(),
            profiles = if (connected) {
                mapOf(AudioProfile.A2DP to RawProfileSnapshot(listOf(address), mapOf(address to AudioConnectionState.CONNECTED)))
            } else {
                emptyMap()
            },
        )
    }

    override fun gatt(address: String): GattDriver {
        if (!connected) throw GattException(GattFailureKind.TRANSIENT, "no gatt in idle test")
        return object : GattDriver {
            override val events = incoming.receiveAsFlow()
            override suspend fun connect() = Unit
            override suspend fun discoverServices() = listOf(GattService(gattProfile.serviceUuid, listOf(writer, notifier)))
            override suspend fun requestMtu(preferred: Int) = 185
            override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) = Unit
            override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) = Unit
            override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
                val hex = value.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
                if (hex.length >= 14) {
                    i5Replies[hex.substring(10, 14)]?.let { reply ->
                        incoming.trySend(GattEvent.Notification(notifier, reply.hexBytes()))
                    }
                }
            }
            override suspend fun close() {
                incoming.close()
            }
        }
    }

    override fun scan() = emptyFlow<Advertisement>()

    override fun spp(address: String, serviceUuid: String): SppLink = throw UnsupportedOperationException("no spp in test")

    fun inject(hex: String) {
        incoming.trySend(GattEvent.Notification(notifier, hex.hexBytes()))
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        val i5Replies = mapOf(
            "0101" to "09ff00000101011100000164620000ffe2",
            "0602" to "09ff00000106020e000000010020",
            "0201" to "09ff00000102010b000118",
            "0603" to "09ff00000106030b00011e",
            "0801" to "09ff00000108010b00011e",
            "0d01" to "09ff0000010d010e00010205002d",
            "0401" to "09ff00000104011200060502040605030746",
            "0a01" to "09ff0000010a010b00001f",
            "0605" to "09ff00000106050e000101010126",
            "0c01" to "09ff0000010c010b000122",
            "0607" to "09ff00000106070b000223",
            "0604" to "09ff00000106040b00001e",
            "0e03" to "09ff0000010e030b000126",
        )
    }
}

internal class ReadyHeadset(val frame: Bundle, val hostState: ControlSnapshot)

internal suspend fun readyHeadset(): ReadyHeadset {
    val target = BridgeTarget.BLUETOOTH
    val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val drivers = TestHeadsetDrivers(connected = true)
        lateinit var runtime: CoreControlRuntime
        val host = ModuleControlHost(
            target.packageName,
            target.packageName,
            "test-host",
            { ClientRole.HOST_PRESENTATION },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base, drivers).also { runtime = it } },
            hostScope,
            { "lease" },
        )
        host.updatePlatform(
            PlatformState(
                permission = PermissionState.GRANTED,
                transport = TransportAvailability.Available,
                hostPlatform = HostPlatform.ANDROID,
                controlTransportApplicability = PlatformApplicability.NOT_APPLICABLE,
                userUnlocked = true,
                scanPermission = PermissionState.GRANTED,
            ),
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), hostScope)
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(target.packageName, target), Binder()).getString(BridgeWire.LEASE_ID))
        awaitCondition("headset session ready") { endpoint.currentSession(lease).getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK }
        var frame: Bundle? = null
        awaitCondition("battery and noise mode to be read") {
            val request = endpoint.currentSession(lease).apply { putString(BridgeWire.LEASE_ID, lease) }
            frame = endpoint.read(request).getBundle(BridgeWire.SNAPSHOT)
            (decodeNativeSnapshot(frame ?: return@awaitCondition false) as? NativeSnapshotDecode.Decoded)?.native?.let { native ->
                native.fields.values.none { it is FieldReading.Loading }
            } == true
        }
        return ReadyHeadset(assertNotNull(frame), runtime.coreState.value)
    } finally {
        hostScope.cancel()
    }
}

internal suspend fun readyHeadsetFrame(): Bundle = readyHeadset().frame

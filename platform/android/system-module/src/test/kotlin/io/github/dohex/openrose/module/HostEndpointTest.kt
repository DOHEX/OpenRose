package io.github.dohex.openrose.module

import android.app.Application
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostConfiguration
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.IntegrationSettings
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HostIdentityWire
import io.github.dohex.openrose.androidintegration.wire.HostWire
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlFrames
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformApplicability
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.UserSettings
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
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import io.github.dohex.openrose.ffi.drivers.SppLink
import io.github.dohex.openrose.ffi.encodeToBytes
import io.github.dohex.openrose.ffi.kotlincore.CoreControlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.openrose_circulation.CirculationCommand
import uniffi.openrose_circulation.CirculationReply
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.modelDisplayName
import uniffi.openrose_ffi.modelGattProfile
import uniffi.openrose_headset.LocalDateTime
import uniffi.openrose_headset.ModelProfile
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class HostEndpointTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closingTheLeaseUsesSynchronousBinderAndEndsTheFrameSubscription() = runTest {
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base) },
            backgroundScope,
            { "lease" },
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val generation = assertIs<HostStatus.Active>(host.status.value).generation
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), backgroundScope)
        val remote = IControlBridge.Stub.asInterface(object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0, flags and IBinder.FLAG_ONEWAY, "an oneway transaction loses the caller's pid")
                return endpoint.transact(code, data, reply, flags)
            }
        })
        val lease = assertNotNull(remote.open(BridgeBundles.hello(null, null), Binder()).getString(BridgeWire.LEASE_ID))
        val request = Bundle().apply {
            putString(BridgeWire.LEASE_ID, lease)
            putString(BridgeWire.PAYLOAD, HostIdentityWire.encode(generation).render())
        }
        var delivered = false
        var ended = false
        val frames = object : IControlFrames.Stub() {
            override fun onFrame(frame: Bundle) {
                delivered = true
            }
            override fun onEnded(end: Bundle) {
                ended = true
            }
        }
        assertEquals(BridgeWire.STATUS_OK, remote.watchFrames(request, frames).getString(BridgeWire.STATUS))
        runCurrent()
        assertTrue(delivered)
        remote.close(lease)
        runCurrent()
        assertTrue(ended)
        assertFailsWith<SecurityException> { endpoint.ownerStatus(request) }
    }

    @Test
    fun circulationDoesNotRunAGenerationNamedRequestOnAReplacementService() = runTest {
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base) },
            backgroundScope,
            { "lease" },
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val active = assertIs<HostStatus.Active>(host.status.value).generation
        var calls = 0
        var service = OwnerCirculation(active.copy(sequence = active.sequence + 1), backgroundScope) {
            calls++
            CirculationReply.Forgotten
        }
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), backgroundScope) { service }
        val previousVersion = BridgeBundles.hello(null, null).apply { putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION - 1) }
        val refused = endpoint.open(previousVersion, Binder())
        assertEquals(BridgeWire.STATUS_UNSUPPORTED_VERSION, refused.getString(BridgeWire.STATUS))
        assertFalse(refused.containsKey(BridgeWire.LEASE_ID))
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(null, null), Binder()).getString(BridgeWire.LEASE_ID))
        val request = Bundle().apply {
            putString(BridgeWire.LEASE_ID, lease)
            putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
            putString(BridgeWire.PAYLOAD, HostIdentityWire.encode(active).render())
            putByteArray(BridgeWire.CIRCULATION, CirculationCommand.Forget("peer").encodeToBytes())
        }
        assertEquals(BridgeWire.STATUS_UNAVAILABLE, endpoint.circulation(request).getString(BridgeWire.STATUS))
        assertEquals(0, calls)

        val callerThread = Thread.currentThread()
        service = OwnerCirculation(active, backgroundScope) {
            assertFalse(Thread.currentThread() === callerThread)
            calls++
            CirculationReply.Forgotten
        }
        assertEquals(BridgeWire.STATUS_OK, endpoint.circulation(request).getString(BridgeWire.STATUS))
        assertEquals(1, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aDeadApplicationEndsOnlyItsFrameSubscription() = runTest {
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base) },
            backgroundScope,
            { "lease" },
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val active = assertIs<HostStatus.Active>(host.status.value)
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), backgroundScope)
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(null, null), Binder()).getString(BridgeWire.LEASE_ID))
        val request = Bundle().apply {
            putString(BridgeWire.LEASE_ID, lease)
            putString(BridgeWire.PAYLOAD, HostIdentityWire.encode(active.generation).render())
        }
        var attempted = 0
        var ended = false
        val receiver = object : IControlFrames.Stub() {
            override fun onFrame(frame: Bundle) {
                attempted++
                throw DeadObjectException()
            }
            override fun onEnded(end: Bundle) {
                ended = true
            }
        }

        assertEquals(BridgeWire.STATUS_OK, endpoint.watchFrames(request, receiver).getString(BridgeWire.STATUS))
        runCurrent()
        assertEquals(1, attempted)
        assertTrue(ended)
        assertEquals(active, host.status.value)
    }

    @Test
    fun onlyAnAcknowledgedAndStillCurrentReadingCanBeRefreshedWithoutItsPayload() = runBlocking<Unit> {
        val drivers = TestHeadsetDrivers(connected = true)
        val target = BridgeTarget.BLUETOOTH
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var runtime: CoreControlRuntime
        val host = ModuleControlHost(
            target.packageName,
            target.packageName,
            "test-host",
            { ClientRole.HOST_PRESENTATION },
            { scope, settings, platform, base ->
                coreRuntime(scope, settings, platform, base, drivers).also { runtime = it }
            },
            hostScope,
            { "lease" },
        )
        try {
            val configuration = HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE)))
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
            host.configure { configuration }
            val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), hostScope)
            val lease = assertNotNull(endpoint.open(BridgeBundles.hello(target.packageName, target), Binder()).getString(BridgeWire.LEASE_ID))
            fun current() = endpoint.currentSession(lease)
            awaitCondition("headset session ready") { current().getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK }

            val request = current().apply { putString(BridgeWire.LEASE_ID, lease) }
            val frame = assertNotNull(endpoint.read(request).getBundle(BridgeWire.SNAPSHOT))
            assertFalse(current().containsKey(BridgeWire.READ_SEQUENCE))
            val seq = frame.getLong(BridgeWire.READ_SEQUENCE)
            val receipt = Bundle(request).apply { putLong(BridgeWire.READ_SEQUENCE, seq) }
            assertEquals(BridgeWire.STATUS_OK, endpoint.acknowledge(receipt).getString(BridgeWire.STATUS))
            val heartbeat = current()
            assertEquals(seq, heartbeat.getLong(BridgeWire.READ_SEQUENCE))
            assertTrue(heartbeat.containsKey(BridgeWire.CAPTURED_ELAPSED_MILLIS))
            assertFalse(heartbeat.containsKey(BridgeWire.SNAPSHOT))

            drivers.inject("09ff00000106040b00011f")
            awaitCondition("game mode change invalidates confirmed read") { !current().containsKey(BridgeWire.READ_SEQUENCE) }

            val refreshedRequest = current().apply { putString(BridgeWire.LEASE_ID, lease) }
            val refreshedFrame = assertNotNull(endpoint.read(refreshedRequest).getBundle(BridgeWire.SNAPSHOT))
            val refreshedSeq = refreshedFrame.getLong(BridgeWire.READ_SEQUENCE)
            assertEquals(
                BridgeWire.STATUS_OK,
                endpoint.acknowledge(Bundle(refreshedRequest).apply { putLong(BridgeWire.READ_SEQUENCE, refreshedSeq) }).getString(BridgeWire.STATUS),
            )
            assertTrue(current().containsKey(BridgeWire.READ_SEQUENCE))

            host.configure { configuration.copy(settings = configuration.settings.copy(integration = configuration.settings.integration.copy(dynamicColor = true))) }
            assertFalse(current().containsKey(BridgeWire.READ_SEQUENCE))

            runtime.disconnect()
            awaitCondition("headset disconnected") { current().getString(BridgeWire.STATUS) == BridgeWire.STATUS_NO_SESSION }
        } finally {
            hostScope.cancel()
        }
    }

    @Test
    fun helloWithWrongCoreWireFormatVersionIsRefusedWithUnsupportedVersion() = runTest {
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base) },
            backgroundScope,
            { "lease" },
        )
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), backgroundScope)
        val wrongWireFormat = BridgeBundles.hello(null, null).apply {
            putInt(BridgeWire.WIRE_FORMAT_VERSION, coreWireFormatVersion().toInt() + 1)
        }
        val refused = endpoint.open(wrongWireFormat, Binder())
        assertEquals(BridgeWire.STATUS_UNSUPPORTED_VERSION, refused.getString(BridgeWire.STATUS))
        assertFalse(refused.containsKey(BridgeWire.LEASE_ID))

        val missingWireFormat = BridgeBundles.hello(null, null).apply {
            remove(BridgeWire.WIRE_FORMAT_VERSION)
        }
        assertEquals(BridgeWire.STATUS_UNSUPPORTED_VERSION, endpoint.open(missingWireFormat, Binder()).getString(BridgeWire.STATUS))
    }

    @Test
    fun executeDecodesCoreCommandBytesAndRejectsMalformedOrUnsupportedVersion() = runTest {
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { scope, settings, platform, base -> coreRuntime(scope, settings, platform, base) },
            backgroundScope,
            { "lease" },
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val generation = assertIs<HostStatus.Active>(host.status.value).generation
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), backgroundScope)
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(null, null), Binder()).getString(BridgeWire.LEASE_ID))

        fun commandRequest(bytes: ByteArray?): Bundle = Bundle().apply {
            putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
            putString(BridgeWire.LEASE_ID, lease)
            putString(BridgeWire.PAYLOAD, HostIdentityWire.encode(generation).render())
            bytes?.let { putByteArray(BridgeWire.COMMAND, it) }
        }

        val okReply = endpoint.execute(commandRequest(HostWire.encode(HostCommand.StartScan)))
        assertEquals(BridgeWire.STATUS_OK, okReply.getString(BridgeWire.STATUS))
        assertIs<HostCallResult.Operation>(HostWire.decodeCallResult(parseWire(okReply.getString(BridgeWire.PAYLOAD).orEmpty())))

        assertEquals(
            BridgeWire.STATUS_INVALID_ARGUMENT,
            endpoint.execute(commandRequest(null)).getString(BridgeWire.STATUS),
        )
        assertEquals(
            BridgeWire.STATUS_INVALID_ARGUMENT,
            endpoint.execute(commandRequest("corrupt".encodeToByteArray())).getString(BridgeWire.STATUS),
        )

        val wrongCoreVersion = HostWire.encode(HostCommand.StartScan).apply {
            this[3] = (coreWireFormatVersion().toInt() + 1).toByte()
        }
        assertEquals(
            BridgeWire.STATUS_UNSUPPORTED_VERSION,
            endpoint.execute(commandRequest(wrongCoreVersion)).getString(BridgeWire.STATUS),
        )
    }
}

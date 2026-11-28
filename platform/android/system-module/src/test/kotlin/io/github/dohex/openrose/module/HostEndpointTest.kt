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
import io.github.dohex.openrose.circulation.CirculationResult
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.LocalControlRuntime
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            { scope, settings, platform, _ -> LocalControlRuntime(platform, settings, scope = scope) },
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
            { scope, settings, platform, _ -> LocalControlRuntime(platform, settings, scope = scope) },
            backgroundScope,
            { "lease" },
        )
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE))) }
        val active = assertIs<HostStatus.Active>(host.status.value).generation
        var calls = 0
        var service = OwnerCirculation(active.copy(sequence = active.sequence + 1), backgroundScope) {
            calls++
            CirculationResult("已解除配对")
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
            putString(BridgeWire.HOST_EPOCH, active.toString())
            putBundle(
                BridgeWire.PAYLOAD,
                Bundle().apply {
                    putString("op", "forget")
                    putString("peer", "peer")
                },
            )
        }
        assertEquals(BridgeWire.STATUS_UNAVAILABLE, endpoint.circulation(request).getString(BridgeWire.STATUS))
        assertEquals(0, calls)

        val callerThread = Thread.currentThread()
        service = OwnerCirculation(active, backgroundScope) {
            assertFalse(Thread.currentThread() === callerThread)
            calls++
            CirculationResult("已解除配对")
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
            { scope, settings, platform, _ -> LocalControlRuntime(platform, settings, scope = scope) },
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
    fun onlyAnAcknowledgedAndStillCurrentReadingCanBeRefreshedWithoutItsPayload() = runTest {
        val profile = ModelProfile.I5_ZT
        val device = DeviceState(DeviceId("AA:BB:CC:DD:EE:FF"), 1, profile, profile.capabilities(), persistentMapOf())
        val state = MutableStateFlow(
            ControlState(
                PlatformState(PermissionState.NOT_REQUIRED),
                ConnectionState.Connected(device.deviceId, device.sessionId, profile),
                device = device,
            ),
        )
        val target = BridgeTarget.BLUETOOTH
        val host = ModuleControlHost(
            target.packageName,
            target.packageName,
            "test-host",
            { ClientRole.HOST_PRESENTATION },
            { scope, settings, platform, _ ->
                object : ControlRuntime by LocalControlRuntime(platform, settings, scope = scope) {
                    override val state = state
                }
            },
            backgroundScope,
            { "lease" },
        )
        val configuration = HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = ControlWay.MODULE)))
        host.configure { configuration }
        val endpoint = HostEndpoint(RuntimeEnvironment.getApplication(), host, MutableStateFlow(HostFacts()), backgroundScope)
        val lease = assertNotNull(endpoint.open(BridgeBundles.hello(target.packageName, target), Binder()).getString(BridgeWire.LEASE_ID))
        fun current() = endpoint.currentSession(lease)
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

        state.value = state.value.copy(
            device = device.copy(diagnostics = device.diagnostics.copy(fields = persistentMapOf(DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(true), "01")))),
        )
        assertFalse(current().containsKey(BridgeWire.READ_SEQUENCE))
        state.value = state.value.copy(device = device)
        host.configure { configuration.copy(settings = configuration.settings.copy(integration = configuration.settings.integration.copy(dynamicColor = true))) }
        assertFalse(current().containsKey(BridgeWire.READ_SEQUENCE))
        state.value = state.value.copy(connection = ConnectionState.Disconnected)
        assertEquals(BridgeWire.STATUS_NO_SESSION, current().getString(BridgeWire.STATUS))
    }

    @Test
    fun helloWithWrongCoreWireFormatVersionIsRefusedWithUnsupportedVersion() = runTest {
        val host = ModuleControlHost(
            "bluetooth",
            "bluetooth",
            "host",
            { ClientRole.APP },
            { scope, settings, platform, _ -> LocalControlRuntime(platform, settings, scope = scope) },
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
            { scope, settings, platform, _ -> LocalControlRuntime(platform, settings, scope = scope) },
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

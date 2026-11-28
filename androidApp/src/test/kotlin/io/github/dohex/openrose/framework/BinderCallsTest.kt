package io.github.dohex.openrose.framework

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostFrame
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostLinkException
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostPlatformFacts
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.LinkOpenResult
import io.github.dohex.openrose.androidintegration.OwnerFailureStage
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HostFrameWire
import io.github.dohex.openrose.androidintegration.wire.HostIdentityWire
import io.github.dohex.openrose.androidintegration.wire.HostSnapshotFormatException
import io.github.dohex.openrose.androidintegration.wire.Wire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.androidintegration.wire.str
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlFrames
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import io.github.dohex.openrose.ffi.encodeToBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_control.ControlSettings
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.HostPlatform
import uniffi.openrose_control.PlatformApplicability
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.ScanState
import java.util.concurrent.CountDownLatch
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class BinderCallsTest {
    @Test
    fun anUnresponsiveTransactionReleasesItsWaiterButKeepsItsSlot() = runBlocking {
        val calls = BinderCalls(1)
        val started = CompletableDeferred<Unit>()
        val abandoned = CompletableDeferred<Int>()
        val release = CountDownLatch(1)
        try {
            val wait = launch {
                calls.run<Int>(onAbandoned = { abandoned.complete(it) }) {
                    started.complete(Unit)
                    release.await()
                    1
                }
            }
            withTimeout(5_000) {
                started.await()
                wait.cancelAndJoin()
            }
            var ran = false
            assertNull(withTimeoutOrNull(50) { calls.run { ran = true } })
            assertFalse(ran)
            release.countDown()
            assertEquals(1, withTimeout(5_000) { abandoned.await() })
            assertEquals(2, withTimeout(5_000) { calls.run { 2 } })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun discoveryTheHostRejectsIsAConnectionFailure() = runBlocking {
        val rejecting = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = throw IllegalStateException("宿主拒绝了发现请求")
        }
        val result = assertIs<LinkOpenResult.Failed>(ModuleHostBinderLink(boundContext(rejecting)).open())
        assertEquals(OwnerFailureStage.CONNECTION, result.stage)
    }

    @Test
    fun hostRejectingFrameSubscriptionSignalsALostLink() = runBlocking {
        val generation = OwnerGeneration("host", 1)
        val status = HostStatus.Active(generation, OwnerHolder(ControlWay.MODULE, generation, "bluetooth", "bluetooth"))
        val host = object : IControlBridge.Default() {
            override fun open(hello: Bundle, clientToken: IBinder): Bundle {
                val authorized = HostOpenResult.Opened(HostLease("lease", ClientRole.APP, generation.incarnation), status)
                return BridgeBundles.payload(BridgeWire.STATUS_OK, HostIdentityWire.encode(authorized).render())
            }

            override fun watchFrames(request: Bundle, frames: IControlFrames): Bundle = throw IllegalArgumentException("invalid subscription")
        }
        val binder = Binder().apply { attachInterface(host, IControlBridge.DESCRIPTOR) }
        val connection = assertIs<LinkOpenResult.Open>(ModuleHostBinderLink(boundContext(binder)).open()).connection
        try {
            val failure = assertFailsWith<HostLinkException> { withTimeout(5_000) { connection.frames(generation).first() } }
            val cause = generateSequence<Throwable>(failure) { it.cause }.last()
            assertEquals("invalid subscription", assertIs<IllegalArgumentException>(cause).message)
        } finally {
            connection.close()
        }
    }

    @Test
    fun malformedOrUnsupportedSnapshotBytesOnBinderFrameSignalALostLink() = runBlocking {
        val generation = OwnerGeneration("host", 1)
        val holder = OwnerHolder(ControlWay.MODULE, generation, "bluetooth", "bluetooth")
        val status = HostStatus.Active(generation, holder)
        val snapshot = emptySnapshot()
        val baseWire = HostFrameWire.encode(
            HostFrame(
                generation,
                holder,
                HostPlatformFacts(PermissionState.GRANTED, TransportAvailability.Available),
                snapshot,
                SystemPresentationState(),
                HostFacts(),
            ),
        ) as Wire.Obj
        val unsupportedBytes = snapshot.encodeToBytes().apply { this[3] = (coreWireFormatVersion().toInt() + 1).toByte() }
        val payloads = listOf(
            Wire.Obj(baseWire.fields + ("snapshot" to str(Base64.encode("corrupt".encodeToByteArray())))).render(),
            Wire.Obj(baseWire.fields + ("snapshot" to str(Base64.encode(unsupportedBytes)))).render(),
        )

        for ((index, payload) in payloads.withIndex()) {
            val connection = assertIs<LinkOpenResult.Open>(ModuleHostBinderLink(boundContext(frameBinder(generation, status, payload))).open()).connection
            try {
                val failure = assertFailsWith<HostLinkException> { withTimeout(5_000) { connection.frames(generation).first() } }
                val cause = assertIs<HostSnapshotFormatException>(
                    generateSequence(failure.cause) { it.cause }.filterIsInstance<HostSnapshotFormatException>().firstOrNull(),
                )
                if (index == 0) {
                    assertIs<CoreDecodeResult.Error.Malformed>(cause.error)
                } else {
                    val unsupported = assertIs<CoreDecodeResult.Error.UnsupportedVersion>(cause.error)
                    assertEquals(coreWireFormatVersion(), unsupported.expected)
                    assertEquals((coreWireFormatVersion().toInt() + 1).toUByte(), unsupported.actual)
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun frameBinder(generation: OwnerGeneration, status: HostStatus, payload: String): IBinder {
        val host = object : IControlBridge.Default() {
            override fun open(hello: Bundle, clientToken: IBinder): Bundle {
                val authorized = HostOpenResult.Opened(HostLease("lease", ClientRole.APP, generation.incarnation), status)
                return BridgeBundles.payload(BridgeWire.STATUS_OK, HostIdentityWire.encode(authorized).render())
            }

            override fun watchFrames(request: Bundle, frames: IControlFrames): Bundle {
                frames.onFrame(Bundle().apply { putString(BridgeWire.PAYLOAD, payload) })
                return BridgeBundles.response(BridgeWire.STATUS_OK)
            }
        }
        return Binder().apply { attachInterface(host, IControlBridge.DESCRIPTOR) }
    }

    private fun emptySnapshot(): ControlSnapshot = ControlSnapshot(
        revision = 0u,
        platform = PlatformState(
            permission = uniffi.openrose_control.PermissionState.GRANTED,
            transport = uniffi.openrose_control.TransportAvailability.Available,
            hostPlatform = HostPlatform.ANDROID,
            userUnlocked = true,
            scanPermission = uniffi.openrose_control.PermissionState.GRANTED,
            controlTransportApplicability = PlatformApplicability.APPLICABLE,
        ),
        settings = ControlSettings(forceBle = false),
        connection = ConnectionState.Disconnected,
        bondedHeadsets = emptyList(),
        candidates = emptyList(),
        scan = ScanState.Idle,
        headset = null,
        lastError = null,
        settingOperations = emptyList(),
        audioHeadsetId = null,
        connectionTarget = null,
        selectedHeadsetId = null,
        connectionRecovery = null,
        connectionBlockReason = null,
        reconnectGuidance = null,
    )

    private fun boundContext(binder: IBinder): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connection.onServiceConnected(requireNotNull(service.component), binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun leavingDuringDiscoveryUnbindsThePendingConnection() = runTest {
        var bindings = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                bindings++
                return true
            }
            override fun unbindService(connection: ServiceConnection) {
                bindings--
            }
        }
        val discovering = launch { ModuleHostBinderLink(context).open() }
        runCurrent()
        assertEquals(1, bindings)
        discovering.cancelAndJoin()
        assertEquals(0, bindings)
    }
}

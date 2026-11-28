package io.github.dohex.openrose.module

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlObserver
import io.github.dohex.openrose.ffi.coreWireFormatVersion
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class AuthenticatedBridgeClientPollTest {
    @Test
    fun unchangedFrameRenewsFreshnessButANewGenerationMustBeReadAndAcknowledged() {
        val target = BridgeTarget.MILINK
        val events = mutableListOf<String>()
        val host = PollingHost(events, runBlocking { readyHeadsetFrame() })
        val client = AuthenticatedBridgeClient(
            bridgeContext(target, host),
            target,
            target.packageName,
            onSnapshot = { frame, native -> events += "snapshot ${frame.getString(BridgeWire.HOST_EPOCH)} ${native.hostEpoch}" },
            onSnapshotFresh = { events += "fresh $it" },
            onClear = { events += "clear" },
            onBonded = {},
        )
        val reader = shadowOf(client.handler.looper)
        reader.pause()
        try {
            client.start()
            reader.idle()
            assertEquals(listOf("watch host#1", "read host#1", "ack host#1", "snapshot host#1 host#1"), events)
            events.clear()
            reader.runToNextTask()
            assertEquals(listOf("fresh 123"), events)
            events.clear()
            host.generation = "host#2"
            reader.runToNextTask()
            assertEquals(listOf("clear", "watch host#2", "read host#2", "ack host#2", "snapshot host#2 host#2"), events)
        } finally {
            reader.unPause()
            assertTrue(client.close())
        }
    }

    @Test
    fun malformedOrForeignVersionCoreBytesClearTheSnapshotAndTheClientKeepsPolling() {
        val target = BridgeTarget.MILINK
        val events = mutableListOf<String>()
        val host = PollingHost(events, runBlocking { readyHeadsetFrame() })
        val client = AuthenticatedBridgeClient(
            bridgeContext(target, host),
            target,
            target.packageName,
            onSnapshot = { frame, _ -> events += "snapshot ${frame.getString(BridgeWire.HOST_EPOCH)}" },
            onSnapshotFresh = {},
            onClear = { events += "clear" },
            onBonded = {},
        )
        val reader = shadowOf(client.handler.looper)
        reader.pause()
        try {
            client.start()
            reader.idle()
            assertEquals(listOf("watch host#1", "read host#1", "ack host#1", "snapshot host#1"), events)

            events.clear()
            host.sequence = 8
            host.coreBytes = { "corrupt".encodeToByteArray() }
            reader.runToNextTask()
            assertEquals(listOf("read host#1", "clear"), events)

            events.clear()
            host.sequence = 9
            host.coreBytes = { it }
            reader.runToNextTask()
            assertEquals(listOf("read host#1", "ack host#1", "snapshot host#1"), events)

            events.clear()
            host.sequence = 10
            host.coreBytes = { it.copyOf().apply { this[3] = (coreWireFormatVersion().toInt() + 1).toByte() } }
            reader.runToNextTask()
            assertEquals(listOf("read host#1", "clear"), events)
        } finally {
            reader.unPause()
            assertTrue(client.close())
        }
    }

    @Test
    fun aNativeCoreThatFailsToLoadIsABridgeFailureAndTheClientKeepsPolling() {
        val target = BridgeTarget.MILINK
        val events = mutableListOf<String>()
        val host = PollingHost(events, runBlocking { readyHeadsetFrame() }).apply {
            openFailure = UnsatisfiedLinkError("dlopen failed: libopenrose_ffi.so")
        }
        val client = AuthenticatedBridgeClient(
            bridgeContext(target, host),
            target,
            target.packageName,
            onSnapshot = { frame, _ -> events += "snapshot ${frame.getString(BridgeWire.HOST_EPOCH)}" },
            onSnapshotFresh = {},
            onClear = {},
            onBonded = {},
        )
        val reader = shadowOf(client.handler.looper)
        reader.pause()
        try {
            client.start()
            reader.idle()
            assertFalse(events.any { it.startsWith("watch") }, "$events")
            host.openFailure = null
            reader.runToNextTask()
            assertTrue("snapshot host#1" in events, "$events")
        } finally {
            reader.unPause()
            assertTrue(client.close())
        }
    }

    private fun bridgeContext(target: BridgeTarget, host: IControlBridge): Context {
        val binder = Binder().apply { attachInterface(host, IControlBridge.DESCRIPTOR) }
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = target.packageName
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                connection.onServiceConnected(requireNotNull(service.component), binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
    }

    private class PollingHost(private val events: MutableList<String>, private val frame: Bundle) : IControlBridge.Default() {
        var generation = "host#1"
        var sequence = 7L
        var openFailure: Throwable? = null
        var coreBytes: (ByteArray) -> ByteArray = { it }

        override fun open(hello: Bundle, clientToken: IBinder): Bundle = openFailure?.let { throw it } ?: BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.LEASE_ID, "lease")
            putString(BridgeWire.HOST_EPOCH, "host")
        }

        override fun currentSession(leaseId: String): Bundle = BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.HOST_EPOCH, generation)
            putString(BridgeWire.DEVICE_ID, frame.getString(BridgeWire.DEVICE_ID))
            putLong(BridgeWire.SESSION_ID, frame.getLong(BridgeWire.SESSION_ID))
            putLong(BridgeWire.READ_SEQUENCE, sequence)
            putLong(BridgeWire.CAPTURED_ELAPSED_MILLIS, 123)
        }

        override fun watch(request: Bundle, observer: IControlObserver): Bundle {
            events += "watch ${request.getString(BridgeWire.HOST_EPOCH)}"
            return BridgeBundles.response(BridgeWire.STATUS_OK)
        }

        override fun read(request: Bundle): Bundle {
            events += "read ${request.getString(BridgeWire.HOST_EPOCH)}"
            return BridgeBundles.response(BridgeWire.STATUS_OK).apply {
                putBundle(
                    BridgeWire.SNAPSHOT,
                    Bundle(frame).apply {
                        putString(BridgeWire.HOST_EPOCH, generation)
                        putLong(BridgeWire.READ_SEQUENCE, sequence)
                        putLong(BridgeWire.CAPTURED_ELAPSED_MILLIS, 123)
                        putByteArray(BridgeWire.CORE_SNAPSHOT, coreBytes(requireNotNull(frame.getByteArray(BridgeWire.CORE_SNAPSHOT))))
                    },
                )
            }
        }

        override fun acknowledge(receipt: Bundle): Bundle {
            events += "ack ${receipt.getString(BridgeWire.HOST_EPOCH)}"
            return BridgeBundles.response(BridgeWire.STATUS_OK)
        }
    }
}

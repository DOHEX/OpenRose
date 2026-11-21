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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class AuthenticatedBridgeClientPollTest {
    @Test
    fun unchangedFrameRenewsFreshnessButANewGenerationMustBeReadAndAcknowledged() {
        val target = BridgeTarget.MILINK
        val events = mutableListOf<String>()
        val host = PollingHost(events)
        val binder = Binder().apply { attachInterface(host, IControlBridge.DESCRIPTOR) }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = target.packageName
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                connection.onServiceConnected(requireNotNull(service.component), binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val client = AuthenticatedBridgeClient(
            context,
            target,
            target.packageName,
            onSnapshot = { events += "snapshot ${it.getString(BridgeWire.HOST_EPOCH)}" },
            onSnapshotFresh = { events += "fresh $it" },
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
            reader.runToNextTask()
            assertEquals(listOf("fresh 123"), events)
            events.clear()
            host.generation = "host#2"
            reader.runToNextTask()
            assertEquals(listOf("clear", "watch host#2", "read host#2", "ack host#2", "snapshot host#2"), events)
        } finally {
            reader.unPause()
            assertTrue(client.close())
        }
    }

    private class PollingHost(private val events: MutableList<String>) : IControlBridge.Default() {
        var generation = "host#1"

        override fun open(hello: Bundle, clientToken: IBinder): Bundle = BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.LEASE_ID, "lease")
            putString(BridgeWire.HOST_EPOCH, "host")
        }

        override fun currentSession(leaseId: String): Bundle = BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.HOST_EPOCH, generation)
            putString(BridgeWire.DEVICE_ID, "device")
            putLong(BridgeWire.SESSION_ID, 1)
            putLong(BridgeWire.READ_SEQUENCE, 7)
            putLong(BridgeWire.CAPTURED_ELAPSED_MILLIS, 123)
        }

        override fun watch(request: Bundle, observer: IControlObserver): Bundle {
            events += "watch ${request.getString(BridgeWire.HOST_EPOCH)}"
            return BridgeBundles.response(BridgeWire.STATUS_OK)
        }

        override fun read(request: Bundle): Bundle {
            events += "read ${request.getString(BridgeWire.HOST_EPOCH)}"
            return BridgeBundles.response(BridgeWire.STATUS_OK).apply {
                putBundle(BridgeWire.SNAPSHOT, currentSession("lease"))
            }
        }

        override fun acknowledge(receipt: Bundle): Bundle {
            events += "ack ${receipt.getString(BridgeWire.HOST_EPOCH)}"
            return BridgeBundles.response(BridgeWire.STATUS_OK)
        }
    }
}

package io.github.dohex.openrose.framework

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.LinkOpenResult
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HostIdentityWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.circulation.CirculationCommand
import io.github.dohex.openrose.circulation.CirculationFailure
import io.github.dohex.openrose.circulation.CirculationReply
import io.github.dohex.openrose.circulation.CirculationResult
import io.github.dohex.openrose.circulation.PairingReply
import io.github.dohex.openrose.circulation.PairingTicket
import io.github.dohex.openrose.circulation.RemoteSessionReference
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.circulationWireFormatVersionNumber
import io.github.dohex.openrose.ffi.encodeToBytes
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.openrose_circulation.PairingOffer
import uniffi.openrose_circulation.Peer
import uniffi.openrose_circulation.RemoteError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import uniffi.openrose_circulation.CirculationCommand as RustCommand
import uniffi.openrose_circulation.CirculationReply as RustReply

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ModuleHostBinderLinkCirculationTest {
    private val generation = OwnerGeneration("host", 7)
    private val holder = OwnerHolder(ControlWay.MODULE, generation, "bluetooth", "bluetooth")
    private val status = HostStatus.Active(generation, holder)

    private class Host(val reply: Bundle) {
        val requests = mutableListOf<Bundle>()
    }

    private fun bridge(host: Host): IBinder {
        val bridge = object : IControlBridge.Default() {
            override fun open(hello: Bundle, clientToken: IBinder): Bundle {
                val authorized = HostOpenResult.Opened(HostLease("lease", ClientRole.APP, generation.incarnation), status)
                return BridgeBundles.payload(BridgeWire.STATUS_OK, HostIdentityWire.encode(authorized).render())
            }

            override fun ownerStatus(request: Bundle): Bundle = BridgeBundles.payload(BridgeWire.STATUS_OK, HostIdentityWire.encode(status).render())

            override fun circulation(request: Bundle): Bundle {
                host.requests += request
                return host.reply
            }
        }
        return Binder().apply { attachInterface(bridge, IControlBridge.DESCRIPTOR) }
    }

    private fun reply(status: String, bytes: ByteArray? = null) = BridgeBundles.response(status).apply {
        bytes?.let { putByteArray(BridgeWire.CIRCULATION, it) }
    }

    private fun exchange(response: Bundle, command: CirculationCommand): Pair<Host, CirculationReply> = runBlocking {
        val host = Host(response)
        val link = ModuleHostBinderLink(boundContext(bridge(host)))
        val connection = assertIs<LinkOpenResult.Open>(link.open()).connection
        try {
            host to link.circulation(command)
        } finally {
            connection.close()
        }
    }

    @Test
    fun aCommandTravelsAsRustBytesWithTheOwnerGenerationAndComesBackAsAUiReply() {
        val offer = PairingOffer("001234", "qr", 10_000uL)
        val (host, result) = exchange(reply(BridgeWire.STATUS_OK, RustReply.Offer(offer).encodeToBytes()), CirculationCommand.Offer)
        assertEquals(PairingReply(PairingTicket("001234", "qr", 10_000L)), result)
        val request = host.requests.single()
        assertEquals("lease", request.getString(BridgeWire.LEASE_ID))
        assertEquals(HostIdentityWire.encode(generation).render(), request.getString(BridgeWire.PAYLOAD))
        assertEquals(RustCommand.Offer.encodeToBytes().toList(), request.getByteArray(BridgeWire.CIRCULATION)?.toList())
    }

    @Test
    fun aNodeFailureComesBackAsTheTextOfItsErrorCode() {
        val (_, result) = exchange(reply(BridgeWire.STATUS_OK, RustReply.Failed(RemoteError.NotPaired).encodeToBytes()), CirculationCommand.Read("peer"))
        assertEquals(CirculationFailure("对端尚未完成流转配对"), result)
    }

    @Test
    fun malformedReplyBytesBecomeAFailure() {
        val garbage = reply(BridgeWire.STATUS_OK, "not circulation".encodeToByteArray())
        assertEquals(CirculationFailure("宿主返回了无法识别的内容"), exchange(garbage, CirculationCommand.Directory).second)
        val missing = reply(BridgeWire.STATUS_OK)
        assertEquals(CirculationFailure("宿主返回了无法识别的内容"), exchange(missing, CirculationCommand.Directory).second)
    }

    @Test
    fun aReplyFromAnotherWireVersionBecomesAnUnsupportedVersionFailure() {
        val bytes = RustReply.Forgotten.encodeToBytes().apply { this[3] = (circulationWireFormatVersionNumber().toInt() + 1).toByte() }
        val result = exchange(reply(BridgeWire.STATUS_OK, bytes), CirculationCommand.Forget("peer")).second
        assertEquals(CirculationFailure("对端互联协议版本不兼容"), result)
    }

    @Test
    fun aRejectedRequestBecomesAFailureNamingTheStatus() {
        for (rejected in listOf(BridgeWire.STATUS_INVALID_ARGUMENT, BridgeWire.STATUS_UNSUPPORTED_VERSION, BridgeWire.STATUS_STALE_HOST)) {
            val result = exchange(reply(rejected), CirculationCommand.Pair("peer", "001234")).second
            assertEquals(CirculationFailure("宿主返回 $rejected"), result)
        }
    }

    @Test
    fun aSettingThatCannotBeRemoteControlledNeverReachesTheHost() {
        val session = RemoteSessionReference("peer", DeviceId("earphones"), "t")
        val command = CirculationCommand.SetSetting(session, HeadsetCommand.Gain(1))
        val (host, result) = exchange(reply(BridgeWire.STATUS_OK, RustReply.Forgotten.encodeToBytes()), command)
        assertEquals(CirculationFailure("不支持远程设置该项"), result)
        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun aPairedReplyBecomesTheConfirmationResult() {
        val peer = Peer("laptop", "电脑")
        val result = exchange(reply(BridgeWire.STATUS_OK, RustReply.Paired(peer).encodeToBytes()), CirculationCommand.Pair("laptop", "001234")).second
        assertEquals(CirculationResult("已配对"), result)
    }

    private fun boundContext(binder: IBinder): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connection.onServiceConnected(requireNotNull(service.component), binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) = Unit
    }
}

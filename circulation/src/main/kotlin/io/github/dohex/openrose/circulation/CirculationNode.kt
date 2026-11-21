package io.github.dohex.openrose.circulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

class CirculationNode(
    directory: File,
    private val name: String,
    private val discovery: PeerDiscovery,
    internal val handoff: HeadsetHandoff?,
    private val handler: (Map<String, String>) -> Map<String, String>,
) : Closeable {
    constructor(directory: File, name: String, discovery: PeerDiscovery, handler: (Map<String, String>) -> Map<String, String>) :
        this(directory, name, discovery, null, handler)

    private val outgoing = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val storage = directory.apply { check(isDirectory || mkdirs()) }
    private val identity = PeerIdentity(storage)
    private val trust = PeerTrust(storage)
    private val pairing = PairingWindow(identity.id)
    private val server = PeerServer(identity.tls, ::accept)

    @Volatile private var routes: List<NearbyPeer> = emptyList()
    private val mutableNearby = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val nearby = mutableNearby.asStateFlow()
    val peers = trust.peers

    @Synchronized
    fun start(listenPort: Int = 0) {
        val port = server.start(listenPort)
        runCatching {
            discovery.start(identity.id, name, port) { peers ->
                routes = peers.filter { it.id != identity.id && ID.matches(it.id) }
                mutableNearby.value = routes.distinctBy { it.id }
            }
        }.onFailure { close() }.getOrThrow()
    }

    fun offerPairing() = pairing.open()

    fun pairQr(qr: String) {
        val parts = requireNotNull(QR.matchEntire(qr)) { "无法识别流转二维码" }.groupValues
        pair(parts[1], parts[2])
    }

    fun pair(id: String, code: String) {
        require(CODE.matches(code)) { "请输入六位配对码" }
        connect(id) { wire ->
            wire.send(mapOf("action" to "pair", "name" to name))
            authenticatePairing(identity.id, id, code, wire.input, wire.output)
            val response = wire.receive()
            synchronized(this) {
                check(server.running) { "互联服务已关闭" }
                trust.add(PeerGrant(PairedPeer(id, response.getValue("name")), response.getValue("grant")))
            }
        }
    }

    fun request(id: String, message: Map<String, String>): Map<String, String> {
        val grant = checkNotNull(trust.grant(id)) { "对端尚未完成流转配对" }
        return connect(id) { wire ->
            wire.send(mapOf("action" to "request", "grant" to grant.token))
            wire.send(message)
            val response = wire.receive()
            check(server.running && trust.grant(id) == grant) { "流转配对已解除" }
            response
        }
    }

    fun forget(id: String) {
        val grant = synchronized(trust) { trust.grant(id)?.also { trust.remove(id) } } ?: return
        // Local revocation is durable even when the remote machine is asleep.
        runCatching {
            connect(id) { wire ->
                wire.send(mapOf("action" to "forget", "grant" to grant.token))
                wire.receive()
            }
        }
    }

    private fun <T> connect(id: String, operation: (PeerWire) -> T): T {
        check(server.running) { "互联服务已关闭" }
        val candidates = routes.filter { it.id == id }
        var unreachable = IOException("无法联系对端")
        for (address in candidates) {
            val socket = identity.tls.socketFactory.createSocket() as SSLSocket
            outgoing.add(socket)
            try {
                check(server.running) { "互联服务已关闭" }
                try {
                    socket.soTimeout = PeerServer.RESPONSE_TIMEOUT_MS
                    socket.connect(InetSocketAddress(address.address, address.port), PeerServer.CONNECT_TIMEOUT_MS)
                    if (PeerIdentity.peer(socket) != id) throw IOException("对端证书与所选设备不匹配")
                } catch (failure: IOException) {
                    unreachable = failure
                    continue
                }
                // Only try another route before the first byte of the request has been delivered.
                return operation(PeerWire(socket))
            } finally {
                outgoing.remove(socket)
                socket.close()
            }
        }
        throw PeerUnreachable(unreachable)
    }

    private fun accept(socket: SSLSocket) {
        val id = PeerIdentity.peer(socket)
        val wire = PeerWire(socket)
        val header = wire.receive()
        if (header["action"] == "pair") {
            val digits = pairing.tryPairing()
            authenticatePairing(identity.id, id, digits, wire.input, wire.output)
            pairing.finish(digits)
            val grant = PeerGrant(PairedPeer(id, header.getValue("name").take(128)), UUID.randomUUID().toString())
            check(server.running)
            trust.add(grant)
            wire.send(mapOf("name" to name, "grant" to grant.token))
            return
        }
        val request = if (header["action"] == "request") wire.receive() else null
        val result = synchronized(trust) {
            val grant = checkNotNull(trust.grant(id))
            check(server.running && grant.token == header["grant"])
            when (header["action"]) {
                "request" -> when (request?.get("op")) {
                    "release" -> handoff?.release(request.getValue("address")) ?: mapOf("error" to "该平台暂不支持流转")
                    else -> handler(checkNotNull(request))
                }

                "forget" -> {
                    trust.remove(id)
                    emptyMap()
                }

                else -> throw IOException("未知的互联操作")
            }
        }
        wire.send(result)
    }

    @Synchronized
    override fun close() {
        server.close()
        outgoing.forEach { runCatching { it.close() } }
        discovery.close()
        routes = emptyList()
        mutableNearby.value = emptyList()
    }

    private companion object {
        val ID = Regex("[0-9a-f]{64}")
        val CODE = Regex("[0-9]{6}")
        val QR = Regex("openrose://pair/([0-9a-f]{64})/([0-9]{6})")
    }
}

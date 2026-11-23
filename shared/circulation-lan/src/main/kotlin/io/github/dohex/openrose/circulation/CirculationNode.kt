package io.github.dohex.openrose.circulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

class CirculationNode internal constructor(
    directory: File,
    private val name: String,
    private val discovery: PeerDiscovery,
    internal val circulation: HeadsetCirculation?,
    private val handler: (Map<String, String>) -> Map<String, String>,
) : Closeable {
    internal constructor(directory: File, name: String, discovery: PeerDiscovery, handler: (Map<String, String>) -> Map<String, String>) :
        this(directory, name, discovery, null, handler)

    constructor(
        directory: File,
        name: String,
        discovery: PeerDiscovery,
        circulation: HeadsetCirculation? = null,
        headset: HeadsetEndpoint? = null,
    ) : this(directory, name, discovery, circulation, { message ->
        headset?.handle(message) ?: mapOf("error" to "该音源设备未提供耳机远程控制")
    })

    private val outgoing = ConcurrentHashMap.newKeySet<Socket>()
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
    internal fun currentOffer() = pairing.current()

    fun pairQr(qr: String) {
        val parts = requireNotNull(QR.matchEntire(qr)) { "无法识别流转二维码" }.groupValues
        pair(parts[1], parts[2])
    }

    fun pair(id: String, code: String) {
        require(CODE.matches(code)) { "请输入六位配对码" }
        connect(id) { wire ->
            wire.send(mapOf("action" to "pair", "name" to name))
            try {
                authenticatePairing(identity.id, id, code, wire.input, wire.output)
            } catch (failure: IOException) {
                throw IOException("配对校验中断，请刷新配对码 · ${failure.localizedMessage ?: "连接已关闭"}", failure)
            }
            val response = wire.receive()
            synchronized(this) {
                check(server.running) { "互联服务已关闭" }
                trust.add(PeerGrant(PairedPeer(id, response.getValue("name")), response.getValue("grant")))
            }
        }
    }

    internal fun request(id: String, message: Map<String, String>): Map<String, String> {
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
        runCatching {
            connect(id) { wire ->
                wire.send(mapOf("action" to "forget", "grant" to grant.token))
                wire.receive()
            }
        }
    }

    private fun <T> connect(id: String, operation: (PeerWire) -> T): T {
        check(server.running) { "互联服务已关闭" }
        val candidates = routes.filter { it.id == id }.distinctBy { it.address to it.port }.sortedBy { it.address !is Inet4Address }
        val failures = mutableListOf<String>()
        for (address in candidates) {
            val secure = try {
                openRoute(address, id)
            } catch (failure: IOException) {
                val family = if (address.address is Inet4Address) "IPv4" else "IPv6"
                failures += "$family · ${failure.localizedMessage}"
                continue
            }
            try {
                return secure.use { operation(PeerWire(it)) }
            } finally {
                outgoing.remove(secure)
            }
        }
        throw PeerUnreachable(IOException(failures.distinct().joinToString(" / ").ifEmpty { "未发现对端地址" }))
    }

    private fun openRoute(address: NearbyPeer, id: String): SSLSocket {
        val raw = address.socketFactory.createSocket()
        outgoing.add(raw)
        var step = "TCP"
        var authenticated = false
        try {
            check(server.running) { "互联服务已关闭" }
            raw.connect(InetSocketAddress(address.address, address.port), PeerServer.CONNECT_TIMEOUT_MS)
            step = "TLS"
            val secure = identity.tls.socketFactory.createSocket(raw, address.address.hostAddress, address.port, true) as SSLSocket
            secure.soTimeout = PeerServer.RESPONSE_TIMEOUT_MS
            check(PeerIdentity.peer(secure) == id) { "对端证书与所选设备不匹配" }
            synchronized(this) {
                check(server.running) { "互联服务已关闭" }
                outgoing.add(secure)
                authenticated = true
            }
            return secure
        } catch (failure: IOException) {
            throw IOException("$step · ${failure.localizedMessage ?: "连接已关闭"}", failure)
        } finally {
            outgoing.remove(raw)
            if (!authenticated) raw.close()
        }
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
                    "release" -> circulation?.release(request.getValue("address")) ?: mapOf("error" to "该平台暂不支持流转")
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

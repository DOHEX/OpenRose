package io.github.dohex.openrose.circulation

import java.io.Closeable
import java.net.InetAddress
import javax.net.SocketFactory

data class PairedPeer(val id: String, val name: String)
data class NearbyPeer(
    val id: String,
    val name: String,
    val address: InetAddress,
    val port: Int,
    val socketFactory: SocketFactory = SocketFactory.getDefault(),
)

interface PeerDiscovery : Closeable {
    fun start(id: String, name: String, port: Int, found: (List<NearbyPeer>) -> Unit)

    companion object {
        const val TYPE = "_openrose._tcp."
        fun serviceName(id: String) = "OpenRose-${id.take(20)}"
    }
}

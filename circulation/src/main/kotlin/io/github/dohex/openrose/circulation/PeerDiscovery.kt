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
    /** Android can resolve this address on a different network from the process default. */
    val socketFactory: SocketFactory = SocketFactory.getDefault(),
)
data class PairingOffer(val code: String, val qr: String)

interface PeerDiscovery : Closeable {
    /** One current snapshot for all visible interfaces. A service can move to another address after wake. */
    fun start(id: String, name: String, port: Int, found: (List<NearbyPeer>) -> Unit)

    companion object {
        const val TYPE = "_openrose._tcp."
        fun serviceName(id: String) = "OpenRose-${id.take(20)}"
    }
}

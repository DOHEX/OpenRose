package io.github.dohex.openrose.circulation

import java.net.InetAddress

class LoopbackDiscoveryNetwork(private val stalePort: Int? = null) {
    private val services = mutableMapOf<NearbyPeer, (List<NearbyPeer>) -> Unit>()

    fun discovery(): PeerDiscovery = object : PeerDiscovery {
        private lateinit var own: NearbyPeer

        override fun start(id: String, name: String, port: Int, found: (List<NearbyPeer>) -> Unit) {
            own = NearbyPeer(id, name, InetAddress.getLoopbackAddress(), port)
            services[own] = found
            publish()
        }

        override fun close() {
            services.remove(own)
            publish()
        }
    }

    private fun publish() {
        services.forEach { (own, receiver) ->
            val others = services.keys.filter { it != own }
            val stale = stalePort?.let { port -> others.map { it.copy(port = port) } }.orEmpty()
            receiver(stale + others)
        }
    }
}

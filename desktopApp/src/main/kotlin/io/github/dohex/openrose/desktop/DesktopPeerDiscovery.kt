package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.circulation.NearbyPeer
import io.github.dohex.openrose.circulation.PeerDiscovery
import javax.jmdns.JmmDNS
import javax.jmdns.NetworkTopologyDiscovery
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class DesktopPeerDiscovery : PeerDiscovery {
    private var dns: JmmDNS? = null
    private val seen = mutableMapOf<Pair<String, String>, List<NearbyPeer>>()

    override fun start(id: String, name: String, port: Int, found: (List<NearbyPeer>) -> Unit) {
        val type = PeerDiscovery.TYPE + "local."
        NetworkTopologyDiscovery.Factory.setClassDelegate { DesktopNetworkTopology() }
        val mdns = JmmDNS.Factory.getInstance()
        dns = mdns
        mdns.addServiceListener(
            type,
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    event.dns.requestServiceInfo(event.type, event.name)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    synchronized(seen) {
                        if (dns !== mdns) return
                        seen.remove(event.dns.inetAddress.hostAddress to event.name)
                        found(seen.values.flatten())
                    }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val peerId = event.info.getPropertyString("id") ?: return
                    if (peerId == id) return
                    val displayName = event.info.getPropertyString("name") ?: return
                    val routes = event.info.inetAddresses.distinct().map { NearbyPeer(peerId, displayName, it, event.info.port) }
                    if (routes.isEmpty()) return
                    synchronized(seen) {
                        if (dns !== mdns) return
                        seen[event.dns.inetAddress.hostAddress to event.name] = routes
                        found(seen.values.flatten())
                    }
                }
            },
        )
        val attributes = mapOf("id" to id, "name" to name.take(60))
        mdns.registerService(ServiceInfo.create(type, PeerDiscovery.serviceName(id), port, 0, 0, attributes))
    }

    override fun close() {
        synchronized(seen) {
            if (dns == null) return
            dns = null
            seen.clear()
        }
        JmmDNS.Factory.close()
    }
}

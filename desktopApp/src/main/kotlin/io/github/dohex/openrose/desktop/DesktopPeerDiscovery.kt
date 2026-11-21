package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.circulation.NearbyPeer
import io.github.dohex.openrose.circulation.PeerDiscovery
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** JmmDNS follows interface additions/removals, including Ethernet and Wi-Fi changes after sleep. */
class DesktopPeerDiscovery : PeerDiscovery {
    private var dns: JmmDNS? = null
    private val seen = mutableMapOf<Pair<String, String>, NearbyPeer>()

    override fun start(id: String, name: String, port: Int, found: (List<NearbyPeer>) -> Unit) {
        val type = PeerDiscovery.TYPE + "local."
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
                        found(seen.values.toList())
                    }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val peerId = event.info.getPropertyString("id") ?: return
                    if (peerId == id) return
                    val displayName = event.info.getPropertyString("name") ?: return
                    val address = event.info.inetAddresses.firstOrNull() ?: return
                    synchronized(seen) {
                        if (dns !== mdns) return
                        seen[event.dns.inetAddress.hostAddress to event.name] = NearbyPeer(peerId, displayName, address, event.info.port)
                        found(seen.values.toList())
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

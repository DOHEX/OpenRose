package io.github.dohex.openrose.desktop

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import javax.jmdns.impl.NetworkTopologyDiscoveryImpl

internal class DesktopNetworkTopology(
    private val addressesByInterface: () -> List<List<InetAddress>> = ::multicastAddresses,
) : NetworkTopologyDiscoveryImpl() {
    override fun getInetAddresses(): Array<InetAddress> = addressesByInterface().mapNotNull { addresses ->
        addresses.minWithOrNull(compareBy<InetAddress>({ it !is Inet4Address }, { it.isLinkLocalAddress }, { it.hostAddress }))
    }.toTypedArray()
}

private fun multicastAddresses(): List<List<InetAddress>> = try {
    val defaultTopology = NetworkTopologyDiscoveryImpl()
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { network ->
        network.inetAddresses.toList().filter { defaultTopology.useInetAddress(network, it) }
    }
} catch (_: SocketException) {
    emptyList()
}

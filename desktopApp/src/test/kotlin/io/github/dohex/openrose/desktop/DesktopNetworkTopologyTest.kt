package io.github.dohex.openrose.desktop

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNetworkTopologyTest {
    @Test
    fun publishesOneEndpointPerLanAndReevaluatesTheAddressesAfterWake() {
        val wifi4 = InetAddress.getByName("192.0.2.4")
        val wifi6 = InetAddress.getByName("2001:db8:1::4")
        val temporary6 = InetAddress.getByName("2001:db8:1::9")
        val ethernet6 = InetAddress.getByName("2001:db8:2::4")
        val ethernet4 = InetAddress.getByName("192.0.2.8")
        var addresses = listOf(listOf(wifi6, temporary6, wifi4), listOf(ethernet6))
        val topology = DesktopNetworkTopology { addresses }
        assertEquals(listOf(wifi4, ethernet6), topology.inetAddresses.toList())

        addresses = listOf(listOf(temporary6, wifi6), listOf(ethernet6, ethernet4))
        assertEquals(listOf(wifi6, ethernet4), topology.inetAddresses.toList())
    }
}

package io.github.dohex.openrose.circulation

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CirculationNodeTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun phonePairsWithDesktopBeforeItCanReadTheCurrentHeadset() {
        val network = LoopbackDiscoveryNetwork()
        var battery = "71"
        CirculationNode(files.newFolder(), "Desktop", network.discovery()) { mapOf("battery" to battery) }.use { desktop ->
            CirculationNode(files.newFolder(), "Phone", network.discovery()) { emptyMap() }.use { phone ->
                desktop.start()
                phone.start()
                val peer = phone.nearby.value.single()
                val read = mapOf("op" to "read")
                assertFails { phone.request(peer.id, read) }
                val offer = desktop.offerPairing()
                phone.pair(peer.id, offer.code)
                assertEquals("Desktop", phone.peers.value.single().name)
                assertEquals("Phone", desktop.peers.value.single().name)
                assertEquals("71", phone.request(peer.id, read)["battery"])
                battery = "68"
                assertEquals("68", phone.request(peer.id, read)["battery"])
            }
        }
    }

    @Test
    fun qrPairingSurvivesBothRestartsAndRevocationStopsReadsInBothDirections() {
        val network = LoopbackDiscoveryNetwork()
        val desktopFiles = files.newFolder()
        val phoneFiles = files.newFolder()
        fun desktop(level: String) = CirculationNode(desktopFiles, "Desk", network.discovery()) { mapOf("left" to level) }
        fun phone() = CirculationNode(phoneFiles, "Phone", network.discovery()) { mapOf("left" to "95") }
        val read = mapOf("op" to "read")
        var desktopId: String
        var phoneId: String
        desktop("98").use { desktop ->
            phone().use { phone ->
                desktop.start()
                phone.start()
                desktopId = phone.nearby.value.single().id
                phoneId = desktop.nearby.value.single().id
                val offer = desktop.offerPairing()
                assertFails { phone.pair(desktopId, if (offer.code == "000000") "111111" else "000000") }
                assertEquals(emptyList(), desktop.peers.value)
                phone.pairQr(offer.qr)
                assertFails { phone.pairQr(offer.qr) }
                assertEquals("98", phone.request(desktopId, read)["left"])
            }
        }
        phone().use { phone ->
            phone.start()
            assertEquals(desktopId, phone.peers.value.single().id)
            assertFails { phone.request(desktopId, read) }
            desktop("54").use { desktop ->
                desktop.start()
                assertEquals("54", phone.request(desktopId, read)["left"])
                assertEquals("95", desktop.request(phoneId, read)["left"])
                phone.forget(desktopId)
                assertEquals(emptyList(), phone.peers.value)
                assertEquals(emptyList(), desktop.peers.value)
                assertFails { phone.request(desktopId, read) }
                assertFails { desktop.request(phoneId, read) }
            }
        }
    }

    @Test
    fun aStaleInterfaceAfterWakeDoesNotHideAReachableAddressForTheSameDesktop() {
        val network = LoopbackDiscoveryNetwork(stalePort = 0)
        CirculationNode(files.newFolder(), "Desk", network.discovery()) { mapOf("current" to "80") }.use { desktop ->
            CirculationNode(files.newFolder(), "Phone", network.discovery()) { emptyMap() }.use { phone ->
                desktop.start()
                phone.start()
                phone.pairQr(desktop.offerPairing().qr)
                assertEquals("80", phone.request(phone.nearby.value.single().id, mapOf("op" to "read"))["current"])
            }
        }
    }

    @Test
    fun closingThePhoneEndpointDiscardsAnOutstandingBatteryRead() {
        val network = LoopbackDiscoveryNetwork()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = Executors.newSingleThreadExecutor()
        try {
            CirculationNode(files.newFolder(), "Desktop", network.discovery()) {
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                mapOf("battery" to "62")
            }.use { desktop ->
                CirculationNode(files.newFolder(), "Phone", network.discovery()) { emptyMap() }.use { phone ->
                    desktop.start()
                    phone.start()
                    phone.pairQr(desktop.offerPairing().qr)
                    val id = phone.peers.value.single().id
                    val pending = reads.submit<Map<String, String>> { phone.request(id, mapOf("op" to "read")) }
                    check(reading.await(5, TimeUnit.SECONDS))
                    phone.close()
                    release.countDown()
                    assertFails { pending.get(5, TimeUnit.SECONDS) }
                }
            }
        } finally {
            release.countDown()
            reads.shutdownNow()
        }
    }

    @Test
    fun pullingAnI5WaitsForReleaseAndBothSidesKeepTheirAudioWhenTheHolderRefuses() {
        val headset = "00:11:22:33:44:55"
        var owner: String? = "Phone"
        var refusal: String? = "通话中"
        var acquireFailure: String? = null
        var desktopCaptureOnly = false
        val events = mutableListOf<String>()
        fun adapter(host: String) = object : CirculationAudio {
            override fun isConnected(address: String) = (owner == host || (host == "Desktop" && desktopCaptureOnly)) && address == headset
            override fun isMediaConnected(address: String) = owner == host && address == headset
            override fun release(address: String) {
                refusal?.let { error(it) }
                events.add("$host release")
                owner = null
            }
            override fun acquire(address: String) {
                check(owner == null)
                acquireFailure?.let { error(it) }
                events.add("$host acquire")
                owner = host
            }
        }
        val network = LoopbackDiscoveryNetwork()
        fun node(host: String) = CirculationNode(
            files.newFolder(),
            host,
            network.discovery(),
            HeadsetCirculation(adapter(host)) { listOf(headset) },
        ) { emptyMap() }
        node("Desktop").use { desktop ->
            node("Phone").use { phone ->
                desktop.start()
                phone.start()
                phone.pairQr(desktop.offerPairing().qr)
                val pull = CirculationCommand.Pull(headset)
                assertEquals("通话中", (desktop.localCommand(pull) as CirculationResult).error)
                assertEquals("Phone", owner)
                refusal = null
                desktopCaptureOnly = true
                desktop.localCommand(pull)
                desktopCaptureOnly = false
                assertEquals(listOf("Phone release", "Desktop acquire"), events)
                refusal = "耳机麦克风正在使用"
                assertEquals(refusal, (phone.localCommand(pull) as CirculationResult).error)
                assertEquals("Desktop", owner)
                refusal = null
                phone.localCommand(pull)
                assertEquals("Phone", owner)
                acquireFailure = "接入超时"
                assertEquals(acquireFailure, (desktop.localCommand(pull) as CirculationResult).error)
                assertEquals(null, owner)
                acquireFailure = null
                assertEquals("本机已接入耳机", (desktop.localCommand(pull) as CirculationResult).message)
                owner = null
                phone.close()
                assertEquals("对端均不可达，本机已接入耳机", (desktop.localCommand(pull) as CirculationResult).message)
                assertEquals("Desktop", owner)
            }
        }
    }
}

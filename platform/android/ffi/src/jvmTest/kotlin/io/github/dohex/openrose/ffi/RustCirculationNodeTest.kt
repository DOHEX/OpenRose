package io.github.dohex.openrose.ffi

import io.github.dohex.openrose.ffi.drivers.ADDRESS
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.SimulatedDrivers
import io.github.dohex.openrose.ffi.drivers.clock
import io.github.dohex.openrose.ffi.drivers.platformState
import io.github.dohex.openrose.ffi.drivers.rig
import io.github.dohex.openrose.ffi.drivers.timing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import uniffi.openrose_circulation.CirculationCommand
import uniffi.openrose_circulation.CirculationReply
import uniffi.openrose_circulation.NearbyPeerRecord
import uniffi.openrose_circulation.PullOutcome
import uniffi.openrose_circulation.RemoteControlStatus
import uniffi.openrose_circulation.RemoteError
import uniffi.openrose_circulation.RemoteSessionReference
import uniffi.openrose_control.ConnectionState
import uniffi.openrose_ffi.CirculationFailure
import uniffi.openrose_ffi.CirculationNode
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.LocalAudio
import uniffi.openrose_ffi.NearbySink
import uniffi.openrose_ffi.OwnerActive
import uniffi.openrose_ffi.PeerDiscovery
import uniffi.openrose_ffi.PeerRoute
import uniffi.openrose_ffi.PeerRouteDialer
import uniffi.openrose_ffi.YieldRefusalKind
import uniffi.openrose_headset.HeadsetCommand
import uniffi.openrose_headset.NoiseMode
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RustCirculationNodeTest {
    private class FakePeerDiscovery : PeerDiscovery {
        private val sink = AtomicReference<NearbySink?>(null)
        val startedWireVersion = AtomicReference<UInt?>(null)

        override suspend fun start(
            peerId: String,
            name: String,
            port: UShort,
            wireVersion: UInt,
            sink: NearbySink,
        ) {
            startedWireVersion.set(wireVersion)
            this.sink.set(sink)
        }

        override suspend fun stop() {
            sink.set(null)
        }

        fun publish(peers: List<NearbyPeerRecord>) {
            sink.get()?.publish(peers)
        }
    }

    private class TcpPeerRoute(
        private val address: String,
        private val port: UShort,
    ) : PeerRoute {
        private val socket = AtomicReference<Socket?>(null)

        override suspend fun connect() {
            val opened = withContext(Dispatchers.IO) {
                Socket(address, port.toInt())
            }
            socket.set(opened)
        }

        override suspend fun read(): ByteArray {
            val current = socket.get() ?: throw DriverException.Terminal("route closed")
            return withContext(Dispatchers.IO) {
                val buf = ByteArray(4096)
                val n = current.getInputStream().read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }

        override suspend fun write(value: ByteArray) {
            val current = socket.get() ?: throw DriverException.Terminal("route closed")
            withContext(Dispatchers.IO) {
                current.getOutputStream().write(value)
                current.getOutputStream().flush()
            }
        }

        override suspend fun closeLink() {
            withContext(Dispatchers.IO) {
                socket.getAndSet(null)?.close()
            }
        }
    }

    private class TcpPeerRouteDialer : PeerRouteDialer {
        val dialedCount = AtomicReference(0)

        override fun openRoute(address: String, port: UShort): PeerRoute {
            dialedCount.set(dialedCount.get() + 1)
            return TcpPeerRoute(address, port)
        }
    }

    private class FakeLocalAudio(initialHolding: Boolean) : LocalAudio {
        val connected = AtomicBoolean(initialHolding)
        val mediaConnected = AtomicBoolean(initialHolding)
        val yieldCalls = CopyOnWriteArrayList<String>()
        val acquireCalls = CopyOnWriteArrayList<String>()
        var refuseYield: DriverException? = null

        override suspend fun isConnected(address: String): Boolean = connected.get()

        override suspend fun isMediaConnected(address: String): Boolean = mediaConnected.get()

        override suspend fun yieldAudio(address: String) {
            refuseYield?.let { throw it }
            yieldCalls.add(address)
            connected.set(false)
            mediaConnected.set(false)
        }

        override suspend fun acquire(address: String) {
            acquireCalls.add(address)
            connected.set(true)
            mediaConnected.set(true)
        }
    }

    private class PairedNodes(
        phoneDir: Path,
        peerDir: Path,
        val phoneControl: RustControlRuntime,
        peerControl: RustControlRuntime,
    ) {
        val phoneDiscovery = FakePeerDiscovery()
        private val peerDiscovery = FakePeerDiscovery()
        val phoneAudio = FakeLocalAudio(initialHolding = true)
        val peerAudio = FakeLocalAudio(initialHolding = false)
        val peerDialer = TcpPeerRouteDialer()
        val phoneOwnerActive = AtomicBoolean(true)
        val phoneNode = node(phoneDir, "Phone", phoneControl, phoneDiscovery, phoneAudio, TcpPeerRouteDialer()) { phoneOwnerActive.get() }
        val peerNode = node(peerDir, "Peer", peerControl, peerDiscovery, peerAudio, peerDialer) { true }

        suspend fun pair() {
            val phonePort = phoneNode.start()
            val peerPort = peerNode.start()
            phoneDiscovery.publish(listOf(NearbyPeerRecord(id = peerNode.id(), name = "Peer", addresses = listOf("127.0.0.1"), port = peerPort)))
            peerDiscovery.publish(listOf(NearbyPeerRecord(id = phoneNode.id(), name = "Phone", addresses = listOf("127.0.0.1"), port = phonePort)))
            withTimeout(2_000) {
                while (phoneNode.nearby().isEmpty() || peerNode.nearby().isEmpty()) delay(10)
            }
            val offer = phoneNode.offerPairing()
            assertEquals(phoneNode.id(), peerNode.pair(phoneNode.id(), offer.code).id)
        }

        suspend fun close() {
            phoneNode.closeNode()
            peerNode.closeNode()
        }
    }

    @Test
    fun aPeerPairsWithThePhoneAndControlsItsHeadsetThroughUniffi() = withPairedNodes {
        assertEquals(1u, phoneDiscovery.startedWireVersion.get())
        assertEquals(1, phoneNode.peers().size)
        assertTrue(peerDialer.dialedCount.get() >= 1)

        val reading = peerNode.remoteRead(phoneNode.id())
        assertEquals(RemoteControlStatus.READY, reading.status)
        val updated = peerNode.remoteSet(phoneNode.id(), requireNotNull(reading.session), HeadsetCommand.NoiseMode(NoiseMode.TRANSPARENCY))
        assertEquals(NoiseMode.TRANSPARENCY, updated.noiseMode)
    }

    @Test
    fun thePhoneRefusesRemoteRequestsWhileItsOwnerIsInactive() = withPairedNodes {
        phoneOwnerActive.set(false)
        val failure = assertFailsWith<CirculationFailure.Failed> { peerNode.remoteRead(phoneNode.id()) }
        assertEquals(RemoteError.OwnerInactive, failure.error)
    }

    @Test
    fun aPullFromThePeerMakesThePhoneYieldAndItsControlFollowsTheAudio() = withPairedNodes {
        val before = phoneNode.handoffStatus(ADDRESS)
        assertTrue(before.available)
        assertTrue(before.mediaReady)
        assertFalse(before.offline)

        assertEquals(PullOutcome.ACQUIRED, peerNode.pull(ADDRESS))
        assertEquals(listOf(ADDRESS), phoneAudio.yieldCalls.toList())
        assertEquals(listOf(ADDRESS), peerAudio.acquireCalls.toList())
        withTimeout(2_000) {
            phoneControl.state.first { it.connection is ConnectionState.Disconnected }
        }
    }

    private fun withPairedNodes(test: suspend PairedNodes.() -> Unit) = runBlocking<Unit> {
        val phoneDir = Files.createTempDirectory("openrose-phone-ffi")
        val peerDir = Files.createTempDirectory("openrose-peer-ffi")
        try {
            rig().use { phoneRig ->
                rig().use { peerRig ->
                    peerRig.audioConnected = false
                    ControlTransportAdapter(phoneRig).use { phoneTransport ->
                        ControlTransportAdapter(peerRig).use { peerTransport ->
                            runtime(phoneRig, phoneTransport).use { phoneControl ->
                                runtime(peerRig, peerTransport).use { peerControl ->
                                    assertEquals(RustControlResult.Success, phoneControl.connect(ADDRESS))
                                    withTimeout(2_000) { phoneControl.state.first { it.connection is ConnectionState.Connected } }
                                    val nodes = PairedNodes(phoneDir, peerDir, phoneControl, peerControl)
                                    try {
                                        nodes.pair()
                                        nodes.test()
                                    } finally {
                                        nodes.close()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            phoneDir.toFile().deleteRecursively()
            peerDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun circulationCodecRoundTripsCommandsAndRepliesAndMapsYieldRefusal() {
        val command = CirculationCommand.Set(
            session = RemoteSessionReference(
                peerId = "peer-1",
                headsetId = ADDRESS,
                token = "tok-1",
            ),
            command = HeadsetCommand.NoiseMode(NoiseMode.ANC),
        )
        val encodedCommand = command.encodeToBytes()
        assertEquals(circulationWireFormatVersionNumber(), encodedCommand[3].toUByte())
        val decodedCommand = assertIs<CoreDecodeResult.Ok<CirculationCommand>>(
            decodeCirculationCommand(encodedCommand),
        )
        assertEquals(command, decodedCommand.value)

        val reply = CirculationReply.Pulled(PullOutcome.ACQUIRED_WITH_PEERS_UNREACHABLE)
        val decodedReply = assertIs<CoreDecodeResult.Ok<CirculationReply>>(
            decodeCirculationReply(reply.encodeToBytes()),
        )
        assertEquals(reply, decodedReply.value)

        assertIs<CoreDecodeResult.Error.Malformed>(
            decodeCirculationCommand("bad".encodeToByteArray()),
        )

        val audio = FakeLocalAudio(initialHolding = true).apply {
            refuseYield = DriverException.YieldRefused(YieldRefusalKind.IN_CALL, "busy")
        }
        val refused = assertFailsWith<DriverException.YieldRefused> {
            runBlocking { audio.yieldAudio(ADDRESS) }
        }
        assertEquals(YieldRefusalKind.IN_CALL, refused.kind)
    }
}

private fun runtime(rig: SimulatedDrivers, transport: ControlTransportAdapter) = RustControlRuntime(
    transport = transport,
    initialPlatformState = rig.platformState(),
    clock = rig.clock(),
    timing = rig.timing(),
)

private fun node(
    dir: Path,
    name: String,
    control: RustControlRuntime,
    discovery: PeerDiscovery,
    audio: LocalAudio,
    dialer: PeerRouteDialer,
    active: () -> Boolean,
) = CirculationNode(
    storageDir = dir.toString(),
    name = name,
    listenPort = 0u,
    control = control.handle,
    discovery = discovery,
    audio = audio,
    dialer = dialer,
    ownerActive = object : OwnerActive {
        override fun isActive(): Boolean = active()
    },
)

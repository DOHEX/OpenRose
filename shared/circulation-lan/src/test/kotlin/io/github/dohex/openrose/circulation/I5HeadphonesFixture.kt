package io.github.dohex.openrose.circulation

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow

internal class I5Headphones {
    val events = mutableListOf<String>()
    var openOwner: String? = null
        private set
    val candidate = DeviceCandidate(DeviceId("11:22:33:44:55:66"), ModelProfile.I5_ZT.displayName, null, ModelProfile.I5_ZT, "", "11:22:33:44:55:66")
    val transport = I5Transport(this)
    val link get() = transport.links.last()

    fun opened() {
        events += "Linux:open"
        openOwner = "Linux"
    }

    fun closed() {
        events += "Linux:close"
        openOwner = null
    }
}

internal class I5Transport(private val headphones: I5Headphones) : ControlTransport {
    val audioDevice = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
    val links = mutableListOf<I5Connection>()
    var autoReplyOnOpen = true

    override val audioConnectedDevice: Flow<DeviceId?> get() = audioDevice
    override suspend fun bondedDevices(): List<DeviceCandidate> = listOf(headphones.candidate)
    override fun scan(): Flow<Advertisement> = emptyFlow()

    override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
        headphones.opened()
        return I5Connection(headphones).also {
            it.initializing = autoReplyOnOpen
            links.add(it)
        }
    }
}

internal class I5Connection(private val headphones: I5Headphones) : ControlConnection {
    override val maxWriteSize = 20
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications = incoming.receiveAsFlow()
    private val sent = MutableStateFlow(emptyList<String>())
    val writes: List<String> get() = sent.value
    var initializing = true
    private var closed = false

    override suspend fun subscribe() = Unit

    override suspend fun write(bytes: ByteArray) {
        val hex = bytes.toHex()
        sent.value += hex
        if (!initializing) return
        i5Replies[hex.substring(10, 14)]?.let { incoming.send(it.fromHex()) }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        initializing = false
        incoming.close()
        headphones.closed()
    }

    suspend fun replyToWrite(frame: String, reply: String) {
        sent.first { it.lastOrNull() == frame }
        deviceReply(reply)
    }

    suspend fun deviceReply(hex: String) = incoming.send(hex.fromHex())
}

private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal val i5Replies = mapOf(
    "0101" to "09ff00000101011100000164620000ffe2",
    "0602" to "09ff00000106020e000000010020",
    "0201" to "09ff00000102010b000118",
    "0603" to "09ff00000106030b00011e",
    "0801" to "09ff00000108010b00011e",
    "0d01" to "09ff0000010d010e00010205002d",
    "0401" to "09ff00000104011200060502040605030746",
    "0a01" to "09ff0000010a010b00001f",
    "0605" to "09ff00000106050e000101010126",
    "0c01" to "09ff0000010c010b000122",
    "0607" to "09ff00000106070b000223",
    "0604" to "09ff00000106040b00001e",
    "0e03" to "09ff0000010e030b000126",
)

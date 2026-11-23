package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.bytes
import io.github.dohex.openrose.core.protocol.hex
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A transport whose control links answer initialization like [profile]'s earphones, for any catalog model.
 * Every notification except the i5's is synthetic (documented offsets, not hardware captures).
 */
internal class CatalogTransport(
    private val profile: ModelProfile,
    private val bonded: List<DeviceCandidate> = emptyList(),
    private val advertisements: Flow<Advertisement> = emptyFlow(),
) : ControlTransport {
    val audioDevice = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
    val links = mutableListOf<CatalogConnection>()

    override val audioConnectedDevice: Flow<DeviceId?> get() = audioDevice
    override suspend fun bondedDevices(): List<DeviceCandidate> = bonded
    override fun scan(): Flow<Advertisement> = advertisements
    override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection = CatalogConnection(profile).also(links::add)
}

internal class CatalogConnection(private val profile: ModelProfile) : ControlConnection {
    override val maxWriteSize = 20
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications = incoming.receiveAsFlow()
    val writes = mutableListOf<String>()
    var initializing = true
    var closed = false

    override suspend fun subscribe() = Unit

    override suspend fun write(bytes: ByteArray) {
        val hex = bytes.hex()
        writes += hex
        if (!initializing) return
        catalogInitializationReplies(profile, hex).forEach { incoming.send(it.bytes()) }
    }

    override suspend fun close() {
        closed = true
        initializing = false
        incoming.close()
    }

    suspend fun deviceReply(hex: String) = incoming.send(hex.bytes())
}

internal fun catalogCandidate(name: String, profile: ModelProfile) = DeviceCandidate(DeviceId(name), profile.displayName, null, profile, "", "11:22:33:44:55:66")

/** An advertisement that identifies [profile] by the first manufacturer-data prefix its configuration declares. */
internal fun catalogAdvertisement(profile: ModelProfile, id: DeviceId): Advertisement {
    val prefix = profile.spec.advertisementPrefixes.first().bytes()
    val company = (prefix[0].toInt() and 255) or ((prefix[1].toInt() and 255) shl 8)
    val name = when (profile) {
        ModelProfile.I5_ZT -> "i5"
        ModelProfile.I7_RS_V2 -> "i7"
        else -> profile.displayName
    }
    return Advertisement(id, name, manufacturerData = mapOf(company to prefix.copyOfRange(2, prefix.size)))
}

/** What [profile]'s earphones send back for one initialization write, as hex frames. */
internal fun catalogInitializationReplies(profile: ModelProfile, writtenHex: String): List<String> = when (profile.family) {
    ProtocolFamily.ZT -> {
        val key = writtenHex.substring(10, 14)
        if (profile == ModelProfile.I5_ZT) listOfNotNull(i5Replies[key]) else listOfNotNull(ztSyntheticReplies[key])
    }

    ProtocolFamily.RS_COMMON_V2 -> rsCommonV2SyntheticReplies

    ProtocolFamily.RS_COMMON -> rsCommonSyntheticReplies

    ProtocolFamily.F8 -> {
        val tag = writtenHex.takeLast(2)
        listOf(
            when (tag) {
                "01" -> "0027010005010050da32"
                "02" -> "002701000602000000007d"
                "05" -> "002701001a05" + "00".repeat(25)
                else -> "0027010003${tag}0000"
            },
        )
    }
}

/** Synthetic ZT notifications, using documented offsets; unknown fields remain unknown. */
private val ztSyntheticReplies = mapOf(
    "0101" to "09ff00000101011100000164640000ff00",
    "0602" to "09ff00000106020e000100000020",
    "0201" to "09ff00000102010a0000",
    "0603" to "09ff00000106030a0000",
    "0801" to "09ff00000108010a0000",
    "0401" to "09ff000001040112000005050505050505",
    "0a01" to "09ff0000010a010a0000",
    "0b01" to "09ff0000010b010a0000",
    "0202" to "09ff0000010202130000000000000000000000",
    "0203" to "09ff00000102030a0001",
    "0d01" to "09ff0000010d010d0001030000",
    "0103" to "09ff00000101030a0050",
    "0802" to "09ff00000108020a0001",
    "0803" to "09ff00000108030a0000",
    "0604" to "09ff00000106040a0001",
    "0e03" to "09ff0000010e030a0003",
    "0605" to "09ff00000106050d0001010101",
)

/** Synthetic legacy RS state covering its shared getInfo tags. */
private val rsCommonSyntheticReplies = listOf(
    "ff00110100010002000300000000000500060000aa",
    "ff00020700aa", "ff00020800aa", "ff00020904aa",
    "ff00040c32465aaa", "ff00040d010203aa", "ff00020e00aa",
    "ff00022a00aa", "ff00022b00aa", "ff00022c01aa",
    "ff00022d01aa", "ff00022e01aa", "ff00023300aa",
)

/** Synthetic RS Common V2 getInfo state, sent whole for every initialization write. */
private val rsCommonV2SyntheticReplies = listOf(
    "dd0011010100020003000400110012001300140043aa",
    "dd00020700e6aa",
    "dd00020801e8aa",
    "dd00020904ecaa",
    "dd00040ce4325053aa",
    "dd00040d020103f4aa",
    "dd00020e00edaa",
    "dd00022a0009aa",
    "dd00022b000aaa",
    "dd00022c030eaa",
    "dd00022d030faa",
    "dd00022e010eaa",
    "dd00022f000eaa",
    "dd0002320011aa",
    "dd0005360000000018aa",
    "dd000b3e86838100010203040506c5aa",
    "dd0002450125aa",
    "dd0002460328aa",
)

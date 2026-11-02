package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.bytes
import io.github.dohex.openrose.core.protocol.hex
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.wire.HostWire
import io.github.dohex.openrose.core.wire.parseWire
import io.github.dohex.openrose.core.wire.render
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal const val APP_PACKAGE = "io.github.dohex.openrose"
internal const val HOST_PACKAGE = "com.android.bluetooth"

/** The physical earphones: exactly one control link may be open at a time, whoever owns it. */
internal class SharedHeadset {
    val events = mutableListOf<String>()
    var openOwner: String? = null
        private set
    var overlappingOpens = 0
        private set

    fun opened(owner: String) {
        events += "$owner:open"
        if (openOwner != null) overlappingOpens++
        openOwner = owner
    }

    fun closed(owner: String) {
        events += "$owner:close"
        if (openOwner == owner) openOwner = null
    }

    fun opens(owner: String) = events.count { it == "$owner:open" }
}

internal class HeadsetTransport(
    private val owner: String,
    private val headset: SharedHeadset,
    private val profile: ModelProfile = ModelProfile.I5_ZT,
    private val bonded: List<DeviceCandidate> = emptyList(),
) : ControlTransport {
    val audioDevice = MutableSharedFlow<DeviceId?>(replay = 1, extraBufferCapacity = 8)
    val links = mutableListOf<HeadsetConnection>()

    override val audioConnectedDevice: Flow<DeviceId?> get() = audioDevice
    override suspend fun bondedDevices(): List<DeviceCandidate> = bonded
    override fun scan(): Flow<Advertisement> = emptyFlow()

    override suspend fun connect(deviceId: DeviceId, endpoint: TransportEndpoint, onPhase: (ConnectionPhase) -> Unit): ControlConnection {
        headset.opened(owner)
        return HeadsetConnection(owner, headset, profile).also(links::add)
    }
}

internal class HeadsetConnection(
    private val owner: String,
    private val headset: SharedHeadset,
    private val profile: ModelProfile,
) : ControlConnection {
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
        if (profile == ModelProfile.I5_ZT) {
            i5Replies[hex.substring(10, 14)]?.let { incoming.send(it.bytes()) }
        } else {
            i7Replies.forEach { incoming.send(it.bytes()) }
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        initializing = false
        incoming.close()
        headset.closed(owner)
    }

    suspend fun deviceReply(hex: String) = incoming.send(hex.bytes())
}

internal class MemoryOwnerStore(var record: OwnerRecord = OwnerRecord()) : OwnerSelectionStore {
    var failSaves = false
    val saved = mutableListOf<OwnerRecord>()
    var onSaved: (OwnerRecord) -> Unit = {}

    override fun load() = record

    override fun save(record: OwnerRecord): Boolean {
        if (failSaves) return false
        this.record = record
        saved += record
        onSaved(record)
        return true
    }
}

internal val appIdentity = CallerIdentity(10_123, 4_321, setOf(APP_PACKAGE), APP_PACKAGE)
internal val milinkIdentity = CallerIdentity(10_050, 999, setOf("com.milink.service"), "com.milink.service")
internal val strangerIdentity = CallerIdentity(10_999, 31_337, setOf("com.example.stranger"), "com.example.stranger")

internal val testAuthority = CallerAuthority { identity ->
    when {
        APP_PACKAGE in identity.packages -> ClientRole.APP
        "com.milink.service" in identity.packages -> ClientRole.MILINK
        else -> null
    }
}

/** Binds the app-side client to a host in the same JVM, carrying every message through the wire codec. */
internal class InProcessHostLink(
    private val host: ModuleControlHost,
    private val scope: CoroutineScope,
    var identity: CallerIdentity = appIdentity,
) : ModuleHostLink {
    var reachable = true
    var releaseHangs = false
    var releaseKillsHostProcess = false
    val connections = mutableListOf<Connection>()

    override suspend fun open(): LinkOpenResult {
        if (!reachable) return LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "找不到模块控制宿主")
        return when (val result = HostWire.decodeOpenResult(parseWire(HostWire.encode(host.open(identity)).render()))) {
            is HostOpenResult.Refused -> LinkOpenResult.Failed(OwnerFailureStage.AUTHORIZATION, result.message)
            is HostOpenResult.Opened -> LinkOpenResult.Open(Connection(result.lease, result.status).also(connections::add))
        }
    }

    inner class Connection(private val lease: HostLease, override val initialStatus: HostStatus) : HostConnection {
        override val incarnation get() = lease.incarnation
        override val ended: CompletableDeferred<LinkEnd> = CompletableDeferred()
        private var open = true
        val isOpen get() = open && !ended.isCompleted

        fun sever(processDied: Boolean) {
            open = false
            ended.complete(LinkEnd(processDied, if (processDied) "宿主进程已结束" else "绑定已断开"))
        }

        private fun ensureOpen() {
            if (!open || !reachable) throw HostLinkException("与宿主失联")
        }

        override suspend fun status(): HostStatus {
            ensureOpen()
            return host.status.value
        }

        override suspend fun execute(generation: OwnerGeneration, command: HostCommand): HostCallResult {
            ensureOpen()
            val sent = HostWire.decodeCommand(parseWire(HostWire.encode(command).render()))
            val result = host.execute(identity, lease.id, generation, sent)
            ensureOpen()
            return HostWire.decodeCallResult(parseWire(HostWire.encode(result).render()))
        }

        override suspend fun release(request: SwitchRequest): ReleaseResult {
            ensureOpen()
            if (releaseKillsHostProcess) {
                sever(processDied = true)
                throw HostLinkException("宿主进程在释放期间结束")
            }
            if (releaseHangs) awaitCancellation()
            val sent = HostWire.decodeRequest(parseWire(HostWire.encode(request).render()))
            val result = host.release(identity, lease.id, sent)
            return HostWire.decodeReleaseResult(parseWire(HostWire.encode(result).render()))
        }

        override suspend fun acquire(request: SwitchRequest, settings: UserSettings): AcquireResult {
            ensureOpen()
            val sent = HostWire.decodeRequest(parseWire(HostWire.encode(request).render()))
            val result = host.acquire(identity, lease.id, sent, HostWire.decodeSettings(parseWire(HostWire.encode(settings).render())))
            return HostWire.decodeAcquireResult(parseWire(HostWire.encode(result).render()))
        }

        override fun frames(generation: OwnerGeneration): Flow<HostFrame> =
            host.frames(identity, lease.id, generation).map { frame ->
                ensureOpen()
                HostWire.decodeFrame(parseWire(HostWire.encode(frame).render()))
            }

        override fun close() {
            open = false
            host.close(identity, lease.id)
        }
    }
}

internal fun rosePair(name: String, profile: ModelProfile = ModelProfile.I5_ZT) =
    DeviceCandidate(DeviceId(name), if (profile == ModelProfile.I5_ZT) "EarFree i5" else "EarFeel i7", null, profile, "", "11:22:33:44:55:66")

/** The application and the module host over one pair of earphones, built the way production wires them. */
internal class OwnerRig(
    private val test: TestScope,
    val earphones: DeviceCandidate = rosePair("rose-a"),
    initial: OwnerRecord = OwnerRecord(),
) {
    val headset = SharedHeadset()
    val appTransport = HeadsetTransport("ordinary", headset, earphones.profile!!, listOf(earphones))
    val hostTransport = HeadsetTransport("module", headset, earphones.profile!!, listOf(earphones))
    val store = MemoryOwnerStore(initial)
    private val ids = generateSequence(1) { it + 1 }.map { "id-$it" }.iterator()
    private val sessionTiming = SessionTiming(queryTimeoutMillis = 50, i5WriteIntervalMillis = 0, i7WriteIntervalMillis = 0)
    private val platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available)

    val host = ModuleControlHost(
        packageName = HOST_PACKAGE, processName = HOST_PACKAGE, incarnation = "host-1",
        authority = testAuthority,
        runtimeFactory = { scope, settings, state -> LocalControlRuntime(state, settings, hostTransport, scope, sessionTiming) },
        parentScope = test.backgroundScope, newId = { "lease-${ids.next()}" }, uid = 1002, pid = 777,
    )
    val link = InProcessHostLink(host, test.backgroundScope)
    val clientTiming = ClientTiming(retryMillis = 1_000, statusPollMillis = 500, releaseTimeoutMillis = 5_000, commandTimeoutMillis = 20_000)

    /** Mirrors the saved choice to the host the way the framework's remote preferences do, asynchronously. */
    private var hostUnlocked = true

    init {
        store.onSaved = { record ->
            test.backgroundScope.launch {
                host.configure(HostConfiguration(hostUnlocked, UserSettings(controlWay = record.selected)))
            }
        }
    }

    suspend fun bootHost(unlocked: Boolean = true) {
        hostUnlocked = unlocked
        host.updatePlatform(platform)
        host.configure(HostConfiguration(unlocked, UserSettings(controlWay = store.record.selected)))
    }

    fun newRuntime(userUnlocked: Boolean = true): SwitchingControlRuntime = SwitchingControlRuntime(
        initialPlatformState = platform,
        initialSettings = UserSettings(),
        store = store,
        ordinaryFactory = { scope, settings, state -> LocalControlRuntime(state, settings, appTransport, scope, sessionTiming) },
        moduleLink = link,
        scope = test.backgroundScope,
        local = LocalOwnerIdentity(APP_PACKAGE, APP_PACKAGE, 10_123, 4_321),
        newId = { ids.next() },
        timing = clientTiming,
        userUnlocked = userUnlocked,
    )

    fun settle() = test.runCurrent()
}

private val i5Replies = mapOf(
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

private val i7Replies = listOf(
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

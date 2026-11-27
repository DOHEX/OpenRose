package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.HandoffWire
import io.github.dohex.openrose.androidintegration.wire.HostFrameWire
import io.github.dohex.openrose.androidintegration.wire.HostIdentityWire
import io.github.dohex.openrose.androidintegration.wire.HostWire
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal const val APP_PACKAGE = "io.github.dohex.openrose"
internal const val HOST_PACKAGE = "com.android.bluetooth"

internal class SharedHeadset {
    private data class Log(val events: List<String> = emptyList(), val openOwner: String? = null, val overlappingOpens: Int = 0)

    private val log = MutableStateFlow(Log())
    val events: List<String> get() = log.value.events
    val openOwner: String? get() = log.value.openOwner
    val overlappingOpens: Int get() = log.value.overlappingOpens

    fun opened(owner: String) = log.update {
        Log(it.events + "$owner:open", owner, if (it.openOwner != null) it.overlappingOpens + 1 else it.overlappingOpens)
    }

    fun closed(owner: String) = log.update {
        Log(it.events + "$owner:close", it.openOwner.takeUnless { open -> open == owner }, it.overlappingOpens)
    }

    fun opens(owner: String) = events.count { it == "$owner:open" }
}

internal class MemoryOwnerStore(var record: OwnerRecord = OwnerRecord()) : OwnerSelectionStore {
    var failSaves = false
    val saved = mutableListOf<OwnerRecord>()
    var hostSelection = record.selected
        private set
    var publicationUnavailable = false
    var onPublished: () -> Unit = {}

    override fun load() = record

    override fun canSave(record: OwnerRecord) = !failSaves

    override fun save(record: OwnerRecord): Boolean {
        if (failSaves) return false
        return OwnerRecordCodec.save(this.record, record, ::publish) { next ->
            this.record = next
            saved += next
            true
        }
    }

    override suspend fun publishSelection(): Boolean = publish()

    private fun publish(): Boolean {
        if (publicationUnavailable) return false
        hostSelection = record.selected
        onPublished()
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

internal class InProcessHostLink(
    var host: ModuleControlHost,
    private val scope: CoroutineScope,
    var identity: CallerIdentity = appIdentity,
) : ModuleHostLink {
    var reachable = true
    var releaseHangs = false
    var pauseNextAcquire = false
    var delayedAcquire: (suspend () -> AcquireResult)? = null
    var releaseKillsHostProcess = false

    var statusHangs = false

    var tamperReceipt: (ReleaseReceipt) -> ReleaseReceipt = { it }
    val connections = mutableListOf<Connection>()

    var openMillis = 0L
    var opens = 0
        private set

    override suspend fun open(): LinkOpenResult {
        opens += 1
        if (openMillis > 0) delay(openMillis)
        if (!reachable) return LinkOpenResult.Failed(OwnerFailureStage.DISCOVERY, "找不到模块控制宿主")
        return when (val result = HostIdentityWire.decodeOpenResult(parseWire(HostIdentityWire.encode(host.open(identity)).render()))) {
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
            if (statusHangs) awaitCancellation()
            return host.status.value
        }

        override suspend fun execute(generation: OwnerGeneration, command: HostCommand): HostCallResult {
            ensureOpen()
            val sent = HostWire.decodeCommand(parseWire(HostWire.encode(command).render()))
            val result = host.control.execute(identity, lease.id, generation, sent)
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
            val sent = HandoffWire.decodeRequest(parseWire(HandoffWire.encode(request).render()))
            val result = host.release(identity, lease.id, sent)
            val delivered = HandoffWire.decodeReleaseResult(parseWire(HandoffWire.encode(result).render()))
            return if (delivered is ReleaseResult.Released) ReleaseResult.Released(tamperReceipt(delivered.receipt)) else delivered
        }

        override suspend fun acquire(request: SwitchRequest): AcquireResult {
            ensureOpen()
            val sent = HandoffWire.decodeRequest(parseWire(HandoffWire.encode(request).render()))
            if (pauseNextAcquire) {
                pauseNextAcquire = false
                delayedAcquire = { host.acquire(identity, lease.id, sent) }
                awaitCancellation()
            }
            val result = host.acquire(identity, lease.id, sent)
            return HandoffWire.decodeAcquireResult(parseWire(HandoffWire.encode(result).render()))
        }

        override fun frames(generation: OwnerGeneration): Flow<HostFrame> = host.control.frames(identity, lease.id, generation).map { frame ->
            ensureOpen()
            HostFrameWire.decodeFrame(parseWire(HostFrameWire.encode(frame).render()))
        }

        override fun close() {
            open = false
            host.close(identity, lease.id)
        }
    }
}

internal fun rosePair(name: String, profile: ModelProfile = ModelProfile.I5_ZT) = DeviceCandidate(DeviceId(name), profile.displayName, null, profile, "", "11:22:33:44:55:66")

internal fun roseAdvertisement(profile: ModelProfile, id: DeviceId): Advertisement {
    val prefix = syntheticAdvertisementPrefixes.getValue(profile).bytes()
    val company = (prefix[0].toInt() and 255) or ((prefix[1].toInt() and 255) shl 8)
    val name = when (profile) {
        ModelProfile.I5_ZT -> "i5"
        ModelProfile.I7_RS_V2 -> "i7"
        else -> profile.displayName
    }
    return Advertisement(id, name, manufacturerData = mapOf(company to prefix.copyOfRange(2, prefix.size)))
}

private val syntheticAdvertisementPrefixes = mapOf(
    ModelProfile.I5_ZT to "525300010001",
    ModelProfile.I7_RS_V2 to "b8e5290f",
)

internal fun initializationReplies(profile: ModelProfile, writtenHex: String): List<String> = when (profile.family) {
    ProtocolFamily.ZT -> listOfNotNull(i5Replies[writtenHex.substring(10, 14)])
    ProtocolFamily.RS_COMMON_V2 -> i7Replies
    ProtocolFamily.RS_COMMON, ProtocolFamily.F8 -> error("${profile.name}: integration tests drive only the i5 and RS Common V2 fixtures; catalog models are covered in shared/headset")
}

internal class OwnerRig(
    private val test: TestScope,
    val earphones: DeviceCandidate = rosePair(APP_HEADSET_ADDRESS),
    initial: OwnerRecord = OwnerRecord(),
    bonded: List<DeviceCandidate> = listOf(earphones),
) {
    val headset = SharedHeadset()
    val app = test.appHeadset(headset, earphones)
    val hostHeadset = test.hostHeadset(headset, earphones, bonded)
    val store = MemoryOwnerStore(initial)
    private val ids = generateSequence(1) { it + 1 }.map { "id-$it" }.iterator()
    private val platform = grantedAndroidPlatform

    private var hostScope: CoroutineScope = newHostScope()
    private var hostCount = 0
    var host = newHost()
        private set
    val link = InProcessHostLink(host, test.backgroundScope)
    val clientTiming = ClientTiming(retryMillis = 1_000, statusPollMillis = 500, releaseTimeoutMillis = 5_000, acquireTimeoutMillis = 5_000, commandTimeoutMillis = 20_000)

    private fun newHostScope() = CoroutineScope(test.backgroundScope.coroutineContext + SupervisorJob(test.backgroundScope.coroutineContext[Job]))

    private fun newHost(): ModuleControlHost {
        hostCount += 1
        return ModuleControlHost(
            packageName = HOST_PACKAGE, processName = HOST_PACKAGE, incarnation = "host-$hostCount",
            authority = testAuthority,
            runtimeFactory = hostHeadset::runtime,
            parentScope = hostScope, newId = { "lease-${ids.next()}" }, uid = 1002, pid = 777 + hostCount,
        )
    }

    suspend fun replaceHost() {
        hostScope.cancel()
        hostHeadset.endProcess()
        hostScope = newHostScope()
        link.connections.filter { it.isOpen }.forEach { it.sever(processDied = true) }
        host = newHost()
        link.host = host
    }

    private var hostUnlocked = true

    init {
        store.onPublished = {
            test.backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                host.configure { HostConfiguration(hostUnlocked, HostSettings(integration = IntegrationSettings(controlWay = store.hostSelection))) }
            }
        }
    }

    suspend fun bootHost(unlocked: Boolean = true) {
        hostUnlocked = unlocked
        host.updatePlatform(platform)
        host.configure { HostConfiguration(unlocked, HostSettings(integration = IntegrationSettings(controlWay = store.hostSelection))) }
        test.quiesce()
    }

    suspend fun unlockHost() {
        hostUnlocked = true
        host.configure { HostConfiguration(true, HostSettings(integration = IntegrationSettings(controlWay = store.hostSelection))) }
        test.quiesce()
    }

    fun newAppProcess() = CoroutineScope(test.backgroundScope.coroutineContext + SupervisorJob(test.backgroundScope.coroutineContext[Job]))

    fun newRuntime(
        userUnlocked: Boolean = true,
        process: CoroutineScope = test.backgroundScope,
        interfaceOpen: Boolean = true,
        integrationPlatform: IntegrationPlatformState = IntegrationPlatformState(),
        settings: HostSettings = HostSettings(),
    ): SwitchingControlRuntime = SwitchingControlRuntime(
        initialPlatformState = platform,
        initialSettings = settings.control,
        store = store,
        appFactory = app::runtime,
        moduleLink = link,
        scope = process,
        local = LocalOwnerIdentity(APP_PACKAGE, APP_PACKAGE, 10_123, 4_321),
        newId = { ids.next() },
        timing = clientTiming,
        userUnlocked = userUnlocked,
        initialIntegrationPlatform = integrationPlatform,
        initialIntegrationSettings = settings.integration,
    ).also { if (interfaceOpen) it.holdAppSession() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun settle() = test.runCurrent()

    fun eventually(what: String, condition: () -> Boolean) = test.eventually(what, condition)

    fun quiesce() = test.quiesce()
}

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

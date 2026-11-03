package io.github.dohex.openrose.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.dohex.openrose.core.BridgeIpcState
import io.github.dohex.openrose.core.CallerAuthority
import io.github.dohex.openrose.core.CallerIdentity
import io.github.dohex.openrose.core.ClientRole
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlWay
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HostCallResult
import io.github.dohex.openrose.core.HostCommand
import io.github.dohex.openrose.core.HostFacts
import io.github.dohex.openrose.core.HostOpenResult
import io.github.dohex.openrose.core.HostRejection
import io.github.dohex.openrose.core.HostReadResult
import io.github.dohex.openrose.core.HostStatus
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.ModuleControlHost
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.OwnerGeneration
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.SessionSnapshotResult
import io.github.dohex.openrose.core.SystemEndpointDiagnostics
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.wire.HostWire
import io.github.dohex.openrose.core.wire.WireFormatException
import io.github.dohex.openrose.core.wire.asStr
import io.github.dohex.openrose.core.wire.parseWire
import io.github.dohex.openrose.core.wire.render
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Who may use the host. Roles come from the verified Binder uid and the packages that own it; nothing
 * the caller says about itself (package, process, resolution) can raise its role, and the application
 * needs no cooperation from anything running in its process.
 */
internal class AndroidCallerAuthority(private val context: Context, private val hostUid: Int = Process.myUid()) : CallerAuthority {
    override fun roleOf(identity: CallerIdentity): ClientRole? = when {
        // The module's own presentation code runs in the host process and therefore under the host uid.
        identity.uid == hostUid -> ClientRole.HOST_PRESENTATION
        BridgeWire.APP_PACKAGE in identity.packages -> ClientRole.APP
        BridgeTarget.MILINK.packageName in identity.packages && isSystemApp(BridgeTarget.MILINK.packageName) -> ClientRole.MILINK
        BridgeTarget.XIAOMI_BLUETOOTH.packageName in identity.packages && isSystemApp(BridgeTarget.XIAOMI_BLUETOOTH.packageName) ->
            ClientRole.XIAOMI_BLUETOOTH
        else -> null
    }

    private fun isSystemApp(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)
}

/**
 * The Binder surface of the module host. It translates the AIDL to the core [ModuleControlHost] and keeps
 * only what is specific to the transport: leases per Binder client, the system entries' read → acknowledge
 * handshake and their reported diagnostics. Authority, ownership and every command stay in the core host.
 */
internal class HostEndpoint(
    private val context: Context,
    private val host: ModuleControlHost,
    private val facts: MutableStateFlow<HostFacts>,
    private val scope: CoroutineScope,
) : IControlBridge.Stub() {
    private val lock = Any()
    private val reads = AtomicLong()
    private val leases = mutableMapOf<String, EndpointLease>()

    private class EndpointLease(
        val id: String,
        val caller: CallerIdentity,
        val role: ClientRole,
        val target: BridgeTarget?,
        val processName: String?,
        val client: IBinder,
        val death: IBinder.DeathRecipient,
        var awaiting: PendingRead? = null,
        var acknowledged: SessionReference? = null,
        var watchJob: Job? = null,
        var framesJob: Job? = null,
    )

    private data class PendingRead(val reference: SessionReference, val sequence: Long, val generation: String)

    init {
        // A report belongs to the session it described; a new owner or an ended one starts clean.
        scope.launch {
            host.ownerView.map { it?.generation }.distinctUntilChanged().collect {
                facts.update { it.copy(presentation = emptyMap()) }
            }
        }
        scope.launch {
            host.ownerView.collect { view ->
                val prompt = view?.state?.presentation?.connectionPrompt
                if (prompt?.mode == IslandMode.OFFICIAL && synchronized(lock) { leases.values.none { it.target == BridgeTarget.XIAOMI_BLUETOOTH } }) {
                    facts.update { it.copy(presentation = it.presentation +
                        (BridgePresentationWire.OFFICIAL_CONNECTION to "UNAVAILABLE · official_target_not_connected")) }
                }
            }
        }
    }

    private fun caller(): CallerIdentity {
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        return CallerIdentity(uid, Binder.getCallingPid(), packages)
    }

    override fun open(hello: Bundle, clientToken: IBinder): Bundle {
        val caller = caller()
        if (hello.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) return BridgeWire.response(BridgeWire.STATUS_UNSUPPORTED_VERSION)
        val opened = host.open(caller)
        if (opened !is HostOpenResult.Opened) {
            val refused = opened as HostOpenResult.Refused
            Log.w(TAG, "open_refused uid=${caller.uid} ${refused.rejection}")
            // A retired instance (module hot reload) is not an authorisation failure: the client must rediscover.
            val status = if (refused.rejection == HostRejection.UNAUTHORIZED) BridgeWire.STATUS_UNAUTHORIZED else BridgeWire.STATUS_UNAVAILABLE
            return BridgeWire.response(status, refused.message)
        }
        val role = opened.lease.role
        val target = when (role) {
            ClientRole.APP -> null
            ClientRole.MILINK -> BridgeTarget.MILINK
            ClientRole.XIAOMI_BLUETOOTH -> BridgeTarget.XIAOMI_BLUETOOTH
            ClientRole.HOST_PRESENTATION -> BridgeTarget.BLUETOOTH
        }
        val processName = hello.getString(BridgeWire.PROCESS)
        if (target != null) {
            // The claimed scope must match the verified caller; it can never widen what the role allows.
            if (hello.getString(BridgeWire.PACKAGE) != target.packageName || processName == null || !target.acceptsProcess(processName)) {
                host.close(caller, opened.lease.id)
                return BridgeWire.response(BridgeWire.STATUS_UNAUTHORIZED, "目标包或运行进程与已验证的调用者不一致")
            }
            val resolution = hello.getString(BridgeWire.RESOLUTION)
            if (resolution !in BridgeWire.RESOLUTIONS) {
                host.close(caller, opened.lease.id)
                return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
            }
            if (resolution != BridgeWire.RESOLUTION_MATCHED) {
                host.close(caller, opened.lease.id)
                recordEndpoint(target, processName, BridgeIpcState.TARGET_INCOMPATIBLE, "${target.componentMatch} · $resolution")
                return BridgeWire.response(BridgeWire.STATUS_TARGET_INCOMPATIBLE)
            }
        }
        val death = IBinder.DeathRecipient { clientDied(opened.lease.id) }
        try {
            clientToken.linkToDeath(death, 0)
        } catch (failure: Exception) {
            host.close(caller, opened.lease.id)
            return BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "调用者已不存在")
        }
        synchronized(lock) {
            // One lease per caller process; a reconnecting client replaces its earlier lease.
            leases.values.filter { it.caller == caller }.map { it.id }.forEach { dropLease(it) }
            leases[opened.lease.id] = EndpointLease(opened.lease.id, caller, role, target, processName, clientToken, death)
        }
        if (target != null && processName != null) recordEndpoint(target, processName, BridgeIpcState.AUTHENTICATED, target.componentMatch)
        return BridgeWire.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.LEASE_ID, opened.lease.id)
            putString(BridgeWire.HOST_EPOCH, opened.lease.incarnation)
            putString(BridgeWire.PAYLOAD, HostWire.encode(opened).render())
        }
    }

    private fun lease(leaseId: String?): EndpointLease {
        val caller = caller()
        return synchronized(lock) { leases[leaseId] }?.takeIf { it.caller == caller }
            ?: throw SecurityException("读取凭据已失效")
    }

    private fun consumerLease(leaseId: String?): EndpointLease =
        lease(leaseId).also { if (it.target == null) throw SecurityException("该调用者无权使用系统入口接口") }

    private fun appLease(request: Bundle): EndpointLease =
        lease(request.getString(BridgeWire.LEASE_ID)).also { if (it.role != ClientRole.APP) throw SecurityException("仅 OpenRose 应用可使用此接口") }

    override fun currentSession(leaseId: String): Bundle {
        val lease = consumerLease(leaseId)
        val view = host.view(lease.caller, lease.id) ?: return BridgeWire.response(BridgeWire.STATUS_NO_SESSION)
        val connection = view.state.connection as? ConnectionState.Connected
        val device = view.state.device
        if (connection == null || device == null || device.deviceId != connection.deviceId || device.sessionId != connection.sessionId) {
            return BridgeWire.response(BridgeWire.STATUS_NO_SESSION)
        }
        return BridgeWire.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.HOST_EPOCH, view.generation.toString())
            putString(BridgeWire.DEVICE_ID, connection.deviceId.value)
            putLong(BridgeWire.SESSION_ID, connection.sessionId)
        }
    }

    private fun generation(request: Bundle): OwnerGeneration? =
        request.getString(BridgeWire.HOST_EPOCH)?.let { text ->
            val split = text.lastIndexOf('#')
            val sequence = text.substring(split + 1).toLongOrNull()
            if (split <= 0 || sequence == null) null else OwnerGeneration(text.substring(0, split), sequence)
        }

    override fun read(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        if (request.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) return BridgeWire.response(BridgeWire.STATUS_UNSUPPORTED_VERSION)
        val generation = generation(request) ?: return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
        val reference = request.reference() ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.readSession(lease.caller, lease.id, generation, reference) }
        return when (result) {
            is HostReadResult.Rejected -> BridgeWire.response(BridgeWire.STATUS_STALE_HOST, result.message)
            is HostReadResult.Available -> when (val snapshot = result.snapshot) {
                is SessionSnapshotResult.Available -> {
                    val sequence = reads.incrementAndGet()
                    val current = SessionReference(snapshot.device.deviceId, snapshot.device.sessionId)
                    synchronized(lock) { lease.awaiting = PendingRead(current, sequence, generation.toString()) }
                    BridgeWire.response(BridgeWire.STATUS_OK).apply {
                        putBundle(BridgeWire.SNAPSHOT, snapshot.device.toBridgeSnapshot(generation.toString(), sequence).apply {
                            putBundle(BridgePresentationWire.PRESENTATION, snapshot.presentation.toBridgePresentation())
                        })
                    }
                }
                is SessionSnapshotResult.Failure -> {
                    synchronized(lock) { lease.awaiting = null }
                    BridgeWire.response(BridgeWire.STATUS_STALE_SESSION, snapshot.error.message)
                }
            }
        }
    }

    override fun acknowledge(receipt: Bundle): Bundle {
        val lease = consumerLease(receipt.getString(BridgeWire.LEASE_ID))
        val pending = synchronized(lock) { lease.awaiting } ?: return BridgeWire.response(BridgeWire.STATUS_NO_PENDING_READ)
        if (receipt.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION || receipt.getString(BridgeWire.HOST_EPOCH) != pending.generation ||
            receipt.getString(BridgeWire.DEVICE_ID) != pending.reference.deviceId.value ||
            receipt.getLong(BridgeWire.SESSION_ID, -1) != pending.reference.sessionId ||
            receipt.getLong(BridgeWire.READ_SEQUENCE, -1) != pending.sequence) {
            return BridgeWire.response(BridgeWire.STATUS_STALE_RECEIPT)
        }
        synchronized(lock) { lease.awaiting = null }
        val generation = generation(receipt) ?: return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
        val still = runBlocking { host.readSession(lease.caller, lease.id, generation, pending.reference) }
        if ((still as? HostReadResult.Available)?.snapshot !is SessionSnapshotResult.Available) {
            return BridgeWire.response(BridgeWire.STATUS_STALE_SESSION)
        }
        synchronized(lock) { lease.acknowledged = pending.reference }
        return BridgeWire.response(BridgeWire.STATUS_OK)
    }

    override fun close(leaseId: String) {
        val lease = lease(leaseId)
        synchronized(lock) { dropLease(lease.id) }
    }

    override fun watch(request: Bundle, observer: IControlObserver): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        synchronized(lock) {
            lease.watchJob?.cancel()
            lease.watchJob = scope.launch {
                host.status.collectLatest { status ->
                    notify(observer, lease.id, (status as? HostStatus.Active)?.generation?.toString())
                    if (status is HostStatus.Active) {
                        host.changes(lease.caller, lease.id, status.generation).collect { notify(observer, lease.id, status.generation.toString()) }
                    }
                }
            }
        }
        return BridgeWire.response(BridgeWire.STATUS_OK)
    }

    private fun notify(observer: IControlObserver, leaseId: String, epoch: String?) {
        runCatching {
            observer.onChanged(Bundle().apply {
                putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
                putString(BridgeWire.LEASE_ID, leaseId)
                epoch?.let { putString(BridgeWire.HOST_EPOCH, it) }
            })
        }
    }

    override fun claimPrompt(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        requireVersion(request)
        val generation = generation(request) ?: return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
        val view = host.view(lease.caller, lease.id)
        val mode = view?.state?.settings?.islandMode ?: return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
        if ((mode == IslandMode.MODULE && lease.target != BridgeTarget.BLUETOOTH) ||
            (mode == IslandMode.OFFICIAL && lease.target != BridgeTarget.XIAOMI_BLUETOOTH) || mode == IslandMode.OFF) {
            return BridgeWire.response(BridgeWire.STATUS_UNAVAILABLE, "该系统入口不拥有当前提示策略")
        }
        val reference = request.reference() ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val id = request.getString(BridgePresentationWire.PROMPT_ID)?.takeIf { it.length in 1..128 }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        return when (val result = runBlocking { host.execute(lease.caller, lease.id, generation, HostCommand.ClaimPrompt(reference, id)) }) {
            is HostCallResult.Flag -> BridgeWire.response(if (result.value) BridgeWire.STATUS_OK else "STALE_PROMPT")
            is HostCallResult.Rejected -> BridgeWire.response(BridgeWire.STATUS_STALE_HOST, result.message)
            is HostCallResult.Operation -> BridgeWire.response(BridgeWire.STATUS_ACTION_FAILED)
        }
    }

    override fun setNoise(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        requireVersion(request)
        val generation = generation(request) ?: return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
        val reference = request.reference() ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val mode = request.getString(BridgePresentationWire.NOISE_MODE)?.takeIf { it.length in 1..64 }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        // The owner validates the generation and session reference under its own lifecycle lock, and the
        // result is the earphones' confirmation, never that this call arrived.
        return when (val result = runBlocking {
            host.execute(lease.caller, lease.id, generation, HostCommand.Set(reference, DeviceSetting.NOISE_MODE, FieldValue.Text(mode)))
        }) {
            is HostCallResult.Operation -> when (val outcome = result.result) {
                OperationResult.Success -> BridgeWire.response(BridgeWire.STATUS_OK)
                is OperationResult.Failure -> BridgeWire.response(outcome.error.code.name, outcome.error.message)
            }
            is HostCallResult.Rejected -> BridgeWire.response(BridgeWire.STATUS_STALE_HOST, result.message)
            is HostCallResult.Flag -> BridgeWire.response(BridgeWire.STATUS_ACTION_FAILED)
        }
    }

    override fun reportComponent(report: Bundle): Bundle {
        val lease = consumerLease(report.getString(BridgeWire.LEASE_ID))
        requireVersion(report)
        val feature = report.getString(BridgePresentationWire.FEATURE)?.takeIf { it in BridgePresentationWire.FEATURES }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val allowed = when (lease.target) {
            BridgeTarget.MILINK -> setOf(BridgePresentationWire.FUSION_CENTER)
            else -> setOf(BridgePresentationWire.SYSTEM_HEADSET, BridgePresentationWire.OFFICIAL_CONNECTION)
        }
        if (feature !in allowed) throw SecurityException("系统入口不能代其他组件报告状态")
        val status = report.getString(BridgeWire.STATUS)?.takeIf { it in BridgePresentationWire.COMPONENT_STATUSES }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val detail = report.getString(BridgePresentationWire.DETAIL)?.takeIf { it.length <= 512 }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val key = endpointKey(requireNotNull(lease.target), lease.processName)
        facts.update { current ->
            val endpoint = current.endpoints[key] ?: return@update current
            current.copy(endpoints = current.endpoints + (key to endpoint.copy(features = endpoint.features + (feature to "$status · $detail"))))
        }
        return BridgeWire.response(BridgeWire.STATUS_OK)
    }

    override fun reportPresentation(report: Bundle): Bundle {
        val lease = consumerLease(report.getString(BridgeWire.LEASE_ID))
        requireVersion(report)
        val generation = generation(report) ?: return BridgeWire.response(BridgeWire.STATUS_STALE_HOST)
        val reference = report.reference() ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val read = runBlocking { host.readSession(lease.caller, lease.id, generation, reference) }
        if ((read as? HostReadResult.Available)?.snapshot !is SessionSnapshotResult.Available) {
            return BridgeWire.response(BridgeWire.STATUS_STALE_SESSION)
        }
        val feature = report.getString(BridgePresentationWire.FEATURE)?.takeIf { it in BridgePresentationWire.FEATURES }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val status = report.getString(BridgeWire.STATUS)?.takeIf { it in BridgePresentationWire.STATUSES }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val detail = report.getString(BridgePresentationWire.DETAIL)?.takeIf { it.length <= 512 }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        facts.update { it.copy(presentation = it.presentation + (feature to "$status · $detail")) }
        return BridgeWire.response(BridgeWire.STATUS_OK)
    }

    // --- application role: the full client surface ---

    override fun ownerStatus(request: Bundle): Bundle {
        appLease(request)
        return BridgeWire.payload(BridgeWire.STATUS_OK, HostWire.encode(host.status.value).render())
    }

    override fun execute(request: Bundle): Bundle {
        val lease = appLease(request)
        val (generation, command) = decodeOrNull { HostWire.decodeCall(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.execute(lease.caller, lease.id, generation, command) }
        return BridgeWire.payload(BridgeWire.STATUS_OK, HostWire.encode(result).render())
    }

    override fun release(request: Bundle): Bundle {
        val lease = appLease(request)
        val switch = decodeOrNull { HostWire.decodeRequest(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.release(lease.caller, lease.id, switch) }
        return BridgeWire.payload(BridgeWire.STATUS_OK, HostWire.encode(result).render())
    }

    override fun acquire(request: Bundle): Bundle {
        val lease = appLease(request)
        val (switch, settings) = decodeOrNull { HostWire.decodeAcquire(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.acquire(lease.caller, lease.id, switch, settings) }
        return BridgeWire.payload(BridgeWire.STATUS_OK, HostWire.encode(result).render())
    }

    override fun watchFrames(request: Bundle, frames: IControlFrames): Bundle {
        val lease = appLease(request)
        val generation = decodeOrNull { HostWire.decodeGeneration(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        synchronized(lock) {
            lease.framesJob?.cancel()
            lease.framesJob = scope.launch {
                try {
                    host.frames(lease.caller, lease.id, generation).collect { frame ->
                        frames.onFrame(Bundle().apply { putString(BridgeWire.PAYLOAD, HostWire.encode(frame).render()) })
                    }
                } finally {
                    runCatching { frames.onEnded(Bundle().apply { putString(BridgeWire.GENERATION, generation.toString()) }) }
                }
            }
        }
        return BridgeWire.response(BridgeWire.STATUS_OK)
    }

    /** Revokes every lease, e.g. when the host owner or the whole endpoint goes away. */
    fun shutdown() {
        synchronized(lock) { leases.keys.toList().forEach(::dropLease) }
    }

    private fun clientDied(leaseId: String) {
        synchronized(lock) { dropLease(leaseId) }
    }

    /** Caller holds [lock]. */
    private fun dropLease(leaseId: String) {
        val lease = leases.remove(leaseId) ?: return
        lease.watchJob?.cancel()
        lease.framesJob?.cancel()
        runCatching { lease.client.unlinkToDeath(lease.death, 0) }
        host.close(lease.caller, lease.id)
        lease.target?.let { target ->
            lease.processName?.let { process ->
                val key = endpointKey(target, process)
                facts.update { current ->
                    val endpoint = current.endpoints[key] ?: return@update current
                    current.copy(endpoints = current.endpoints + (key to endpoint.copy(ipc = BridgeIpcState.DISCONNECTED)))
                }
            }
        }
    }

    private fun recordEndpoint(target: BridgeTarget, process: String, ipc: BridgeIpcState, entryMatch: String) {
        val info = runCatching { context.packageManager.getPackageInfo(target.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val version = info?.let { "${it.versionName ?: "未知"} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()})" } ?: "未知"
        val key = endpointKey(target, process)
        facts.update { current ->
            current.copy(endpoints = current.endpoints + (key to SystemEndpointDiagnostics(
                target.packageName, process, version, entryMatch, ipc, current.endpoints[key]?.features.orEmpty(),
            )))
        }
    }

    private fun endpointKey(target: BridgeTarget, process: String?) = "${target.packageName}@$process"

    private fun requireVersion(request: Bundle) {
        if (request.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) throw SecurityException("请求版本不匹配")
    }

    private fun Bundle.reference(): SessionReference? {
        val id = getString(BridgeWire.DEVICE_ID)?.takeIf { it.length in 1..128 } ?: return null
        val sessionId = getLong(BridgeWire.SESSION_ID, -1).takeIf { it > 0 } ?: return null
        return SessionReference(DeviceId(id), sessionId)
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (_: WireFormatException) {
        null
    }

    private companion object { const val TAG = "HostEndpoint" }
}

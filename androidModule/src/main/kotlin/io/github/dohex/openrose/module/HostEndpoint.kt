package io.github.dohex.openrose.module

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.BondedHeadsets
import io.github.dohex.openrose.androidintegration.BridgeIpcState
import io.github.dohex.openrose.androidintegration.CallerAuthority
import io.github.dohex.openrose.androidintegration.CallerIdentity
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.FusionEntry
import io.github.dohex.openrose.androidintegration.FusionEntryDecision
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostReadResult
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.IslandMode
import io.github.dohex.openrose.androidintegration.ModuleControlHost
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerView
import io.github.dohex.openrose.androidintegration.SessionSnapshotResult
import io.github.dohex.openrose.androidintegration.SystemEndpointDiagnostics
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.HostWire
import io.github.dohex.openrose.androidintegration.wire.WireFormatException
import io.github.dohex.openrose.androidintegration.wire.asStr
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.androidintegration.wire.toBridgePresentation
import io.github.dohex.openrose.androidintegration.wire.toBridgeSnapshot
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlFrames
import io.github.dohex.openrose.bridge.IControlObserver
import io.github.dohex.openrose.bridge.toBundle
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * Who may use the host. Roles come from the verified Binder uid/pid and the packages that own the uid;
 * nothing the caller says about itself (package, process, resolution) can raise its role, and the
 * application needs no cooperation from anything running in its process.
 *
 * com.xiaomi.bluetooth shares android.uid.bluetooth (uid 1002) with the host's com.android.bluetooth, so
 * the uid alone cannot tell the host's own presentation code from the Xiaomi Bluetooth process; the pid does.
 */
internal class AndroidCallerAuthority(
    private val context: Context,
    private val hostUid: Int = Process.myUid(),
    private val hostPid: Int = Process.myPid(),
) : CallerAuthority {
    override fun roleOf(identity: CallerIdentity): ClientRole? = when {
        // The module's own presentation code runs in the host process: same uid and same pid.
        identity.uid == hostUid && identity.pid == hostPid -> ClientRole.HOST_PRESENTATION

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

internal class OwnerCirculation(
    val generation: OwnerGeneration,
    val scope: CoroutineScope,
    val call: (Map<String, String>) -> Map<String, String>,
)

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
    private val circulationOwner: () -> OwnerCirculation? = { null },
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
        var confirmedRead: PendingRead? = null,
        var watchJob: Job? = null,
        var framesJob: Job? = null,
    )

    private data class PendingRead(
        val reference: SessionReference,
        val sequence: Long,
        val generation: OwnerGeneration,
        val device: DeviceState,
        val presentation: SystemPresentationState,
    ) {
        fun matches(view: OwnerView): Boolean = generation == view.generation &&
            device === view.state.device && presentation == view.presentation

        fun matches(receipt: Bundle): Boolean = receipt.getString(BridgeWire.HOST_EPOCH) == generation.toString() &&
            receipt.reference() == reference && receipt.getLong(BridgeWire.READ_SEQUENCE, -1) == sequence
    }

    init {
        // A report belongs to the session it described; a new owner or an ended one starts clean.
        scope.launch {
            host.ownerView.map { it?.generation }.distinctUntilChanged().collect {
                facts.update { it.copy(presentation = emptyMap()) }
            }
        }
        scope.launch {
            host.ownerView.collect { view ->
                val prompt = view?.presentation?.prompt
                if (prompt?.mode == IslandMode.OFFICIAL &&
                    synchronized(lock) { leases.values.none { it.target == BridgeTarget.XIAOMI_BLUETOOTH } }
                ) {
                    facts.update {
                        it.copy(
                            presentation = it.presentation +
                                (BridgePresentationWire.OFFICIAL_CONNECTION to "UNAVAILABLE · official_target_not_connected"),
                        )
                    }
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
        if (hello.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) return BridgeBundles.response(BridgeWire.STATUS_UNSUPPORTED_VERSION)
        return when (val result = host.open(caller)) {
            is HostOpenResult.Opened -> {
                val role = result.lease.role
                val target = when (role) {
                    ClientRole.APP -> null
                    ClientRole.MILINK -> BridgeTarget.MILINK
                    ClientRole.XIAOMI_BLUETOOTH -> BridgeTarget.XIAOMI_BLUETOOTH
                    ClientRole.HOST_PRESENTATION -> BridgeTarget.BLUETOOTH
                }
                val lease = EndpointLease(
                    result.lease.id,
                    caller,
                    role,
                    target,
                    hello.getString(BridgeWire.PROCESS),
                    clientToken,
                    IBinder.DeathRecipient { clientDied(result.lease.id) },
                )
                validateTarget(hello, lease) ?: registerLease(lease, result)
            }

            is HostOpenResult.Refused -> {
                Log.w(TAG, "open_refused uid=${caller.uid} ${result.rejection}")
                // A retired instance (module hot reload) must be rediscovered.
                val status = if (result.rejection == HostRejection.UNAUTHORIZED) {
                    BridgeWire.STATUS_UNAUTHORIZED
                } else {
                    BridgeWire.STATUS_UNAVAILABLE
                }
                BridgeBundles.response(status, result.message)
            }
        }
    }

    private fun validateTarget(hello: Bundle, lease: EndpointLease): Bundle? {
        val target = lease.target ?: return null
        val process = lease.processName
        // The claimed scope must match the verified caller; it can never widen what the role allows.
        if (hello.getString(BridgeWire.PACKAGE) != target.packageName || process == null || !target.acceptsProcess(process)) {
            host.close(lease.caller, lease.id)
            return BridgeBundles.response(BridgeWire.STATUS_UNAUTHORIZED, "目标包或运行进程与已验证的调用者不一致")
        }
        return when (val resolution = hello.getString(BridgeWire.RESOLUTION)) {
            BridgeWire.RESOLUTION_MATCHED -> null

            in BridgeWire.RESOLUTIONS -> {
                host.close(lease.caller, lease.id)
                recordEndpoint(target, process, BridgeIpcState.TARGET_INCOMPATIBLE, "${target.componentMatch} · $resolution")
                BridgeBundles.response(BridgeWire.STATUS_TARGET_INCOMPATIBLE)
            }

            else -> {
                host.close(lease.caller, lease.id)
                BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
            }
        }
    }

    private fun registerLease(lease: EndpointLease, opened: HostOpenResult.Opened): Bundle {
        // The lease is registered before the death link, so a death in between finds and drops it.
        synchronized(lock) {
            // One lease per caller process; a reconnecting client replaces its earlier lease.
            leases.values.filter { it.caller == lease.caller }.map { it.id }.forEach { dropLease(it) }
            leases[lease.id] = lease
        }
        try {
            lease.client.linkToDeath(lease.death, 0)
        } catch (failure: Exception) {
            // Drops the endpoint lease and closes the host lease.
            synchronized(lock) { dropLease(lease.id) }
            Log.w(TAG, "lease_link_failed uid=${lease.caller.uid}", failure)
            return BridgeBundles.response(BridgeWire.STATUS_UNAVAILABLE, "调用者已不存在")
        }
        if (lease.target != null && lease.processName != null) {
            recordEndpoint(lease.target, lease.processName, BridgeIpcState.AUTHENTICATED, lease.target.componentMatch)
        }
        return BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.LEASE_ID, lease.id)
            putString(BridgeWire.HOST_EPOCH, opened.lease.incarnation)
            putString(BridgeWire.PAYLOAD, HostWire.encode(opened).render())
        }
    }

    private fun lease(leaseId: String?): EndpointLease {
        val caller = caller()
        return synchronized(lock) { leases[leaseId] }?.takeIf { it.caller == caller }
            ?: throw SecurityException("读取凭据已失效")
    }

    private fun consumerLease(leaseId: String?): EndpointLease = lease(leaseId).also {
        if (it.target == null) throw SecurityException("该调用者无权使用系统入口接口")
    }

    private fun appLease(request: Bundle): EndpointLease = lease(request.getString(BridgeWire.LEASE_ID)).also {
        if (it.role != ClientRole.APP) throw SecurityException("仅 OpenRose 应用可使用此接口")
    }

    override fun currentSession(leaseId: String): Bundle {
        val lease = consumerLease(leaseId)
        val capturedAt = SystemClock.elapsedRealtime()
        val view = host.view(lease.caller, lease.id) ?: return BridgeBundles.response(BridgeWire.STATUS_NO_SESSION)
        // Bonded ROSE earphones are named with or without a session: on the target source device of a headset
        // circulation they are not connected yet (ADR 0017).
        val bonded = ArrayList(BondedHeadsets.addresses(view.state))
        val reference = view.state.readySession
        if (reference == null) {
            return BridgeBundles.response(BridgeWire.STATUS_NO_SESSION).apply { putStringArrayList(BridgeWire.BONDED_ADDRESSES, bonded) }
        }
        return BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putString(BridgeWire.HOST_EPOCH, view.generation.toString())
            putString(BridgeWire.DEVICE_ID, reference.deviceId.value)
            putLong(BridgeWire.SESSION_ID, reference.sessionId)
            val last = synchronized(lock) { lease.confirmedRead }
            if (last != null && last.matches(view)) {
                putLong(BridgeWire.READ_SEQUENCE, last.sequence)
                putLong(BridgeWire.CAPTURED_ELAPSED_MILLIS, capturedAt)
            }
            putStringArrayList(BridgeWire.BONDED_ADDRESSES, bonded)
        }
    }

    private fun generation(request: Bundle): OwnerGeneration? = request.getString(BridgeWire.HOST_EPOCH)?.let { text ->
        val split = text.lastIndexOf('#')
        val sequence = text.substring(split + 1).toLongOrNull()
        if (split <= 0 || sequence == null) null else OwnerGeneration(text.substring(0, split), sequence)
    }

    override fun read(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        if (request.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) {
            return BridgeBundles.response(BridgeWire.STATUS_UNSUPPORTED_VERSION)
        }
        val generation = generation(request) ?: return BridgeBundles.response(BridgeWire.STATUS_STALE_HOST)
        val reference = request.reference() ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.readSession(lease.caller, lease.id, generation, reference) }
        return when (result) {
            is HostReadResult.Rejected -> BridgeBundles.response(BridgeWire.STATUS_STALE_HOST, result.message)

            is HostReadResult.Available -> when (val snapshot = result.snapshot) {
                is SessionSnapshotResult.Available -> {
                    val sequence = reads.incrementAndGet()
                    synchronized(lock) {
                        lease.awaiting = PendingRead(reference, sequence, generation, snapshot.device, snapshot.presentation)
                    }
                    BridgeBundles.response(BridgeWire.STATUS_OK).apply {
                        putBundle(
                            BridgeWire.SNAPSHOT,
                            (
                                snapshot.device.toBridgeSnapshot(generation.toString(), sequence, SystemClock.elapsedRealtime()) +
                                    (BridgePresentationWire.PRESENTATION to snapshot.presentation.toBridgePresentation())
                                ).toBundle(),
                        )
                    }
                }

                is SessionSnapshotResult.Failure -> {
                    synchronized(lock) { lease.awaiting = null }
                    BridgeBundles.response(BridgeWire.STATUS_STALE_SESSION, snapshot.error.message)
                }
            }
        }
    }

    override fun acknowledge(receipt: Bundle): Bundle {
        val lease = consumerLease(receipt.getString(BridgeWire.LEASE_ID))
        val pending = synchronized(lock) { lease.awaiting } ?: return BridgeBundles.response(BridgeWire.STATUS_NO_PENDING_READ)
        if (receipt.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION || !pending.matches(receipt)) {
            return BridgeBundles.response(BridgeWire.STATUS_STALE_RECEIPT)
        }
        val view = host.view(lease.caller, lease.id)
        if (view?.generation != pending.generation || view.state.readySession != pending.reference) {
            return BridgeBundles.response(BridgeWire.STATUS_STALE_SESSION)
        }
        return synchronized(lock) {
            if (lease.awaiting !== pending) return@synchronized BridgeBundles.response(BridgeWire.STATUS_STALE_RECEIPT)
            lease.awaiting = null
            lease.confirmedRead = pending
            BridgeBundles.response(BridgeWire.STATUS_OK)
        }
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
                        host.changes(lease.caller, lease.id, status.generation).collect {
                            notify(observer, lease.id, status.generation.toString())
                        }
                    }
                }
            }
        }
        return BridgeBundles.response(BridgeWire.STATUS_OK)
    }

    private fun notify(observer: IControlObserver, leaseId: String, epoch: String?) {
        runCatching {
            observer.onChanged(
                Bundle().apply {
                    putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
                    putString(BridgeWire.LEASE_ID, leaseId)
                    epoch?.let { putString(BridgeWire.HOST_EPOCH, it) }
                },
            )
        }
    }

    private fun withSessionReference(request: Bundle, action: (OwnerGeneration, SessionReference) -> Bundle): Bundle {
        val generation = generation(request) ?: return BridgeBundles.response(BridgeWire.STATUS_STALE_HOST)
        val reference = request.reference() ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        return action(generation, reference)
    }

    override fun claimPrompt(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        requireVersion(request)
        return withSessionReference(request) { generation, reference ->
            val id = request.getString(BridgePresentationWire.PROMPT_ID)?.takeIf { it.length in 1..128 }
                ?: return@withSessionReference BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
            val result = runBlocking { host.execute(lease.caller, lease.id, generation, HostCommand.ClaimPrompt(reference, id)) }
            when (result) {
                is HostCallResult.Flag -> BridgeBundles.response(if (result.value) BridgeWire.STATUS_OK else "STALE_PROMPT")

                // The host decides which entry presents the saved island mode; a refusal is not a stale generation.
                is HostCallResult.Rejected -> if (result.rejection == HostRejection.FORBIDDEN) {
                    BridgeBundles.response(BridgeWire.STATUS_UNAVAILABLE, result.message)
                } else {
                    BridgeBundles.response(BridgeWire.STATUS_STALE_HOST, result.message)
                }

                is HostCallResult.Operation -> BridgeBundles.response(BridgeWire.STATUS_ACTION_FAILED)
            }
        }
    }

    override fun setNoise(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        requireVersion(request)
        return withSessionReference(request) { generation, reference ->
            val mode = request.getString(BridgePresentationWire.NOISE_MODE)?.takeIf { it.length in 1..64 }
                ?: return@withSessionReference BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
            // The owner validates the generation and session reference under its own lifecycle lock, and the
            // result is the earphones' confirmation, never that this call arrived.
            val command = HostCommand.Set(reference, DeviceSetting.NOISE_MODE, FieldValue.Text(mode))
            val result = runBlocking { host.execute(lease.caller, lease.id, generation, command) }
            when (result) {
                is HostCallResult.Operation -> when (val outcome = result.result) {
                    OperationResult.Success -> BridgeBundles.response(BridgeWire.STATUS_OK)
                    is OperationResult.Failure -> BridgeBundles.response(outcome.error.code.name, outcome.error.message)
                }

                is HostCallResult.Rejected -> when (result.rejection) {
                    HostRejection.FORBIDDEN, HostRejection.INVALID_LEASE ->
                        BridgeBundles.response(BridgeWire.STATUS_UNAUTHORIZED, result.message)

                    else -> BridgeBundles.response(BridgeWire.STATUS_STALE_HOST, result.message)
                }

                is HostCallResult.Flag -> BridgeBundles.response(BridgeWire.STATUS_ACTION_FAILED)
            }
        }
    }

    /**
     * The fusion device center's additional settings entry. Decided only from the owner's own state (the user's
     * persisted destination and the earphones it knows), so it needs nothing from the application process and
     * never names a device the owner does not know.
     */
    override fun fusionEntry(request: Bundle): Bundle {
        val lease = consumerLease(request.getString(BridgeWire.LEASE_ID))
        requireVersion(request)
        if (lease.target != BridgeTarget.MILINK) throw SecurityException("仅融合设备中心可查询附加设置入口")
        val address = request.getString(BridgeWire.FUSION_ADDRESS)?.takeIf { it.length <= 64 }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val decision = host.view(lease.caller, lease.id)?.let { view ->
            FusionEntry.resolve(view.state, view.settings, address)
        } ?: FusionEntryDecision.System
        return BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            when (decision) {
                FusionEntryDecision.System -> putString(BridgeWire.FUSION_DECISION, BridgeWire.FUSION_SYSTEM)

                is FusionEntryDecision.OpenRose -> {
                    putString(BridgeWire.FUSION_DECISION, BridgeWire.FUSION_OPENROSE)
                    putString(BridgeWire.DEVICE_ID, decision.deviceId.value)
                }
            }
        }
    }

    override fun reportComponent(report: Bundle): Bundle {
        val lease = consumerLease(report.getString(BridgeWire.LEASE_ID))
        requireVersion(report)
        val feature = report.getString(BridgePresentationWire.FEATURE)?.takeIf { it in BridgePresentationWire.FEATURES }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val allowed = when (lease.target) {
            BridgeTarget.MILINK -> setOf(BridgePresentationWire.FUSION_CENTER)
            else -> setOf(BridgePresentationWire.SYSTEM_HEADSET, BridgePresentationWire.OFFICIAL_CONNECTION)
        }
        if (feature !in allowed) throw SecurityException("系统入口不能代其他组件报告状态")
        return recordReport(report, BridgePresentationWire.COMPONENT_STATUSES) { description ->
            val key = endpointKey(requireNotNull(lease.target), lease.processName)
            facts.update { current ->
                val endpoint = current.endpoints[key] ?: return@update current
                val updated = endpoint.copy(features = endpoint.features + (feature to description))
                current.copy(endpoints = current.endpoints + (key to updated))
            }
        }
    }

    override fun reportPresentation(report: Bundle): Bundle {
        val lease = consumerLease(report.getString(BridgeWire.LEASE_ID))
        requireVersion(report)
        val feature = report.getString(BridgePresentationWire.FEATURE)?.takeIf { it in BridgePresentationWire.FEATURES }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        // Each entry reports only the presentation features it renders itself: the host's own renderer the
        // status notification and the connection prompts, the Xiaomi system app only the official prompt.
        val allowed = when (lease.target) {
            BridgeTarget.BLUETOOTH -> setOf(
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.OFFICIAL_CONNECTION,
            )

            BridgeTarget.XIAOMI_BLUETOOTH -> setOf(BridgePresentationWire.OFFICIAL_CONNECTION)

            else -> emptySet()
        }
        if (feature !in allowed) throw SecurityException("系统入口不能代其他组件报告展示状态")
        return withSessionReference(report) { generation, reference ->
            val read = runBlocking { host.readSession(lease.caller, lease.id, generation, reference) }
            if ((read as? HostReadResult.Available)?.snapshot !is SessionSnapshotResult.Available) {
                BridgeBundles.response(BridgeWire.STATUS_STALE_SESSION)
            } else {
                recordReport(report, BridgePresentationWire.STATUSES) { description ->
                    facts.update { it.copy(presentation = it.presentation + (feature to description)) }
                }
            }
        }
    }

    private fun recordReport(report: Bundle, validStatuses: Set<String>, update: (String) -> Unit): Bundle {
        val status = report.getString(BridgeWire.STATUS)?.takeIf { it in validStatuses }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val detail = report.getString(BridgePresentationWire.DETAIL)?.takeIf { it.length <= 512 }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        update("$status · $detail")
        return BridgeBundles.response(BridgeWire.STATUS_OK)
    }

    // --- application role: the full client surface ---

    override fun circulation(request: Bundle): Bundle {
        appLease(request)
        requireVersion(request)
        val owner = circulationOwner()
        val generation = (host.status.value as? HostStatus.Active)?.generation
        if (owner == null || owner.generation != generation || owner.generation.toString() != request.getString(BridgeWire.HOST_EPOCH)) {
            return BridgeBundles.response(BridgeWire.STATUS_UNAVAILABLE, "模块控制尚未就绪")
        }
        val payload = request.getBundle(BridgeWire.PAYLOAD)
        return if (payload == null) BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT) else circulationResponse(owner, payload)
    }

    private fun circulationResponse(owner: OwnerCirculation, data: Bundle): Bundle {
        val fields = data.keySet().associateWith { data.getString(it).orEmpty() }
        val response = try {
            // Authorize on the incoming Binder thread. System calls must run under the Bluetooth host identity.
            runBlocking {
                owner.scope.async(Dispatchers.IO) {
                    check((host.status.value as? HostStatus.Active)?.generation == owner.generation) { "所有者代际已过期" }
                    owner.call(fields)
                }.await()
            }
        } catch (failure: Exception) {
            mapOf("error" to (failure.localizedMessage ?: "互联操作失败"))
        }
        if ((host.status.value as? HostStatus.Active)?.generation != owner.generation) {
            return BridgeBundles.response(BridgeWire.STATUS_UNAVAILABLE, "所有者代际已过期")
        }
        return BridgeBundles.response(BridgeWire.STATUS_OK).apply {
            putBundle(BridgeWire.PAYLOAD, Bundle().apply { response.forEach { (key, value) -> putString(key, value) } })
        }
    }

    override fun ownerStatus(request: Bundle): Bundle {
        appLease(request)
        return BridgeBundles.payload(BridgeWire.STATUS_OK, HostWire.encode(host.status.value).render())
    }

    override fun execute(request: Bundle): Bundle {
        val lease = appLease(request)
        val (generation, command) = decodeOrNull { HostWire.decodeCall(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.execute(lease.caller, lease.id, generation, command) }
        return BridgeBundles.payload(BridgeWire.STATUS_OK, HostWire.encode(result).render())
    }

    override fun release(request: Bundle): Bundle {
        val lease = appLease(request)
        val switch = decodeOrNull { HostWire.decodeRequest(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.release(lease.caller, lease.id, switch) }
        return BridgeBundles.payload(BridgeWire.STATUS_OK, HostWire.encode(result).render())
    }

    override fun acquire(request: Bundle): Bundle {
        val lease = appLease(request)
        val switch = decodeOrNull { HostWire.decodeRequest(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val result = runBlocking { host.acquire(lease.caller, lease.id, switch) }
        return BridgeBundles.payload(BridgeWire.STATUS_OK, HostWire.encode(result).render())
    }

    override fun watchFrames(request: Bundle, frames: IControlFrames): Bundle {
        val lease = appLease(request)
        val generation = decodeOrNull { HostWire.decodeGeneration(parseWire(request.getString(BridgeWire.PAYLOAD).orEmpty())) }
            ?: return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        synchronized(lock) {
            lease.framesJob?.cancel()
            lease.framesJob = scope.launch {
                try {
                    host.frames(lease.caller, lease.id, generation).collect { frame ->
                        frames.onFrame(Bundle().apply { putString(BridgeWire.PAYLOAD, HostWire.encode(frame).render()) })
                    }
                } catch (_: RemoteException) {
                    // An application that has exited can no longer receive this subscription.
                } finally {
                    runCatching { frames.onEnded(Bundle().apply { putString(BridgeWire.GENERATION, generation.toString()) }) }
                }
            }
        }
        return BridgeBundles.response(BridgeWire.STATUS_OK)
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
        val version = info?.let {
            val code = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            "${it.versionName ?: "未知"} ($code)"
        } ?: "未知"
        val key = endpointKey(target, process)
        facts.update { current ->
            current.copy(
                endpoints = current.endpoints + (
                    key to SystemEndpointDiagnostics(
                        target.packageName,
                        process,
                        version,
                        entryMatch,
                        ipc,
                        current.endpoints[key]?.features.orEmpty(),
                    )
                    ),
            )
        }
    }

    private fun endpointKey(target: BridgeTarget, process: String?) = "${target.packageName}@$process"

    private fun requireVersion(request: Bundle) {
        if (request.getInt(BridgeWire.KEY_VERSION) != BridgeWire.VERSION) throw SecurityException("请求版本不匹配")
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (_: WireFormatException) {
        null
    }

    private companion object {
        const val TAG = "HostEndpoint"
    }
}

private fun Bundle.reference(): SessionReference? {
    val id = getString(BridgeWire.DEVICE_ID)?.takeIf { it.length in 1..128 } ?: return null
    val sessionId = getLong(BridgeWire.SESSION_ID, -1).takeIf { it > 0 } ?: return null
    return SessionReference(DeviceId(id), sessionId)
}

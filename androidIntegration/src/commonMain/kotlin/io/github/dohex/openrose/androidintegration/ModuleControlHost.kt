package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.HostPlatformFacts
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the host knows about the user's persisted intent. Nothing is owned before [userUnlocked]. */
data class HostConfiguration(
    val userUnlocked: Boolean = false,
    val settings: HostSettings = HostSettings(),
)

sealed interface HostReadResult {
    data class Available(val snapshot: SessionSnapshotResult) : HostReadResult
    data class Rejected(val rejection: HostRejection, val message: String) : HostReadResult
}

/**
 * The module-side owner of the single control session (ADR 0011), hosted in the system Bluetooth
 * process. It owns one runtime per owner generation and nothing outside it may connect or write.
 *
 * Authority and authorisation are separate from discovery: callers are verified by the injected
 * [authority] from the platform-reported identity, every lease belongs to one verified caller and
 * one host incarnation, and every command names the owner generation it was issued for. No
 * credential is needed from the OpenRose application process.
 */
class ModuleControlHost(
    private val packageName: String,
    private val processName: String,
    private val incarnation: String,
    private val authority: CallerAuthority,
    private val runtimeFactory: (CoroutineScope, UserSettings, PlatformState, Long) -> ControlRuntime,
    parentScope: CoroutineScope,
    private val newId: () -> String,
    private val uid: Int? = null,
    private val pid: Int? = null,
    private val maxLeases: Int = 16,
    private val facts: StateFlow<HostFacts> = MutableStateFlow(HostFacts()),
    private val presentationTiming: PresentationTiming = PresentationTiming(),
) {
    private class Owner(
        val generation: OwnerGeneration,
        val holder: OwnerHolder,
        val job: Job,
        val runtime: SystemEntryRuntime,
        val ended: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class Lease(val id: String, val role: ClientRole, val caller: CallerIdentity)

    private val hostContext = parentScope.coroutineContext
    private val ownership = Mutex()
    private val owner = MutableStateFlow<Owner?>(null)
    private val leases = MutableStateFlow<Map<String, Lease>>(emptyMap())
    private var configuration = HostConfiguration()
    private var platform = PlatformState(PermissionState.NOT_REQUIRED)
    private var held: String? = null
    private var stopped = false
    private val retired = MutableStateFlow(false)
    private var lastGeneration: OwnerGeneration? = null
    private var sequence = 0L
    private val mutableStatus = MutableStateFlow<HostStatus>(HostStatus.Inactive(incarnation, null, "尚未配置"))

    val status: StateFlow<HostStatus> = mutableStatus.asStateFlow()

    /** The current owner's state, null while nobody owns the session. For the host's own bookkeeping, not for clients. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val ownerView: Flow<OwnerView?> = owner.flatMapLatest { active ->
        active?.let { current -> current.runtime.views(current.generation) } ?: flowOf(null)
    }

    /** Applies persisted user intent. The selected way alone decides whether an owner exists. */
    suspend fun configure(next: HostConfiguration) = ownership.withLock {
        configuration = next
        reconcile()
    }

    /** Host-side platform facts (adapter power, permissions) the owner's runtime should see. */
    suspend fun updatePlatform(next: PlatformState) = ownership.withLock {
        platform = next
        owner.value?.runtime?.updatePlatformState(next)
    }

    /**
     * Ends this host instance, e.g. on module hot reload: the owner is released like any other release
     * and the instance never owns again. A replacement instance starts from the persisted intent. Returns
     * whether no owner remains because its connection was confirmed closed. Only then is the instance
     * retired (later [open] calls are refused and every lease stops meaning anything); when the close cannot
     * be confirmed the instance keeps serving and keeps the connection owned, so it is never left without
     * an owner, and a later [stop], release or configuration closes it again.
     */
    suspend fun stop(): Boolean = ownership.withLock {
        stopped = true
        var released = false
        try {
            reconcile()
            released = owner.value == null
        } finally {
            if (released) retired.value = true else stopped = false
            publishStatus()
        }
        released
    }

    /** Verifies the caller and issues a lease. Reaching this endpoint alone grants nothing. */
    fun open(caller: CallerIdentity): HostOpenResult {
        if (retired.value) return HostOpenResult.Refused(HostRejection.INVALID_REQUEST, "控制宿主已停止，请重新发现")
        val role = authority.roleOf(caller)
            ?: return HostOpenResult.Refused(HostRejection.UNAUTHORIZED, "调用者未被授权使用控制宿主")
        var refused = false
        val id = newId()
        leases.update { current ->
            if (current.size >= maxLeases) {
                refused = true
                current
            } else {
                current + (id to Lease(id, role, caller))
            }
        }
        if (refused) return HostOpenResult.Refused(HostRejection.INVALID_REQUEST, "控制宿主连接数已满")
        return HostOpenResult.Opened(HostLease(id, role, incarnation), status.value)
    }

    fun close(caller: CallerIdentity, leaseId: String) {
        leases.update { current -> current[leaseId]?.takeIf { it.caller == caller }?.let { current - leaseId } ?: current }
    }

    suspend fun execute(
        caller: CallerIdentity,
        leaseId: String,
        generation: OwnerGeneration,
        command: HostCommand,
    ): HostCallResult {
        val lease = leaseFor(caller, leaseId)
            ?: return HostCallResult.Rejected(HostRejection.INVALID_LEASE, "控制宿主授权无效或已过期")
        if (!lease.role.allows(command)) return HostCallResult.Rejected(HostRejection.FORBIDDEN, "该调用者无权执行此操作")
        val current = currentOwner(generation)
        if (current is OwnerCheck.Rejected) return HostCallResult.Rejected(current.rejection, current.message)
        val active = (current as OwnerCheck.Current).owner
        if (command is HostCommand.ClaimPrompt && !lease.role.presents(active.runtime.settings.islandMode)) {
            return HostCallResult.Rejected(HostRejection.FORBIDDEN, "该系统入口不拥有当前提示策略")
        }
        return try {
            // Running under the owner's job lets a release cancel and join this very command first.
            withContext(active.job) { dispatch(active.runtime, systemRouted(lease.role, active.runtime, command)) }
        } catch (e: CancellationException) {
            if (active.job.isCancelled) HostCallResult.Rejected(HostRejection.NO_OWNER, "控制所有者已释放") else throw e
        }
    }

    /** A system entry's "off" tap follows the user's choice of writing wind reduction. */
    private fun systemRouted(role: ClientRole, runtime: SystemEntryRuntime, command: HostCommand): HostCommand {
        if (role != ClientRole.MILINK && role != ClientRole.XIAOMI_BLUETOOTH) return command
        if (command !is HostCommand.Set || command.setting != DeviceSetting.NOISE_MODE || command.reference == null) return command
        val requested = (command.value as? FieldValue.Text)?.value ?: return command
        if (!runtime.settings.fusionOffAsWind) return command
        val modes = (runtime.readSession(command.reference) as? SessionSnapshotResult.Available)
            ?.device?.capabilities?.noiseControl?.modes ?: return command
        val mode = SystemHeadsetMapping.requestedMode(requested, offAsWind = true, supportedModes = modes)
        return if (mode == requested) command else command.copy(value = FieldValue.Text(mode))
    }

    /** Reads a detached snapshot, only for the named owner generation and a still-current device session. */
    suspend fun readSession(
        caller: CallerIdentity,
        leaseId: String,
        generation: OwnerGeneration,
        reference: SessionReference,
    ): HostReadResult {
        leaseFor(caller, leaseId) ?: return HostReadResult.Rejected(HostRejection.INVALID_LEASE, "控制宿主授权无效或已过期")
        return when (val current = currentOwner(generation)) {
            is OwnerCheck.Rejected -> HostReadResult.Rejected(current.rejection, current.message)
            is OwnerCheck.Current -> HostReadResult.Available(current.owner.runtime.readSession(reference))
        }
    }

    /** The current owner state for any authorised role; null when the lease is invalid or nobody owns the session. */
    fun view(caller: CallerIdentity, leaseId: String): OwnerView? {
        leaseFor(caller, leaseId) ?: return null
        val active = owner.value ?: return null
        return active.runtime.view(active.generation)
    }

    /**
     * A wake-up whenever the named owner's state changes, for any authorised role; completes when the
     * generation ends. Consumers still read through an authorised, reference-checked read.
     */
    fun changes(caller: CallerIdentity, leaseId: String, generation: OwnerGeneration): Flow<OwnerView> {
        val lease = leaseFor(caller, leaseId)
        val active = owner.value?.takeIf { it.generation == generation }
        if (lease == null || active == null) {
            return callbackFlow {
                close()
                awaitClose()
            }
        }
        return callbackFlow {
            val collector = launch { active.runtime.views(active.generation).collect { send(it) } }
            launch {
                active.ended.await()
                collector.cancel()
                close()
            }
            awaitClose { collector.cancel() }
        }
    }

    /**
     * The owner's state, only for a lease of the app role. Completes when the owner generation ends
     * or the caller is not authorised, so a consumer never keeps treating an ended owner as current.
     */
    fun frames(caller: CallerIdentity, leaseId: String, generation: OwnerGeneration): Flow<HostFrame> {
        val lease = leaseFor(caller, leaseId)
        val active = owner.value?.takeIf { it.generation == generation }
        if (lease == null || lease.role != ClientRole.APP || active == null) {
            return callbackFlow {
                close()
                awaitClose()
            }
        }
        return callbackFlow {
            val collector = launch {
                combine(active.runtime.views(active.generation), facts) { view, reported ->
                    HostFrame(
                        view.generation,
                        active.holder,
                        HostPlatformFacts(view.state.platform.permission, view.state.platform.transport),
                        view.state,
                        view.presentation,
                        reported,
                    )
                }.collect { send(it) }
            }
            launch {
                active.ended.await()
                collector.cancel()
                close()
            }
            awaitClose { collector.cancel() }
        }
    }

    /**
     * Releases the current owner for [request]: authority is given up only after the old connection is
     * closed and its operations are invalidated. The receipt is the only proof a requester may use.
     */
    suspend fun release(caller: CallerIdentity, leaseId: String, request: SwitchRequest): ReleaseResult {
        val lease = leaseFor(caller, leaseId)
            ?: return ReleaseResult.Refused(HostRejection.INVALID_LEASE, "控制宿主授权无效或已过期")
        if (lease.role != ClientRole.APP) return ReleaseResult.Refused(HostRejection.FORBIDDEN, "只有 OpenRose 应用可以请求交接")
        return ownership.withLock {
            val current = owner.value
            val named = request.fromGeneration
            if (current != null) {
                // A proof must cover the owner that really holds the session; an unnamed or other generation proves nothing.
                if (named != current.generation) {
                    return@withLock ReleaseResult.Refused(
                        HostRejection.STALE_GENERATION,
                        if (named == null) "请求未指明所有者代际" else "请求引用的所有者代际已过期",
                        status.value,
                    )
                }
                held = request.id
                if (!stop(current)) {
                    held = null
                    return@withLock ReleaseResult.Refused(HostRejection.RELEASE_FAILED, "无法确认控制连接已关闭", status.value)
                }
                publishStatus()
                ReleaseResult.Released(ReleaseReceipt(request.id, incarnation, current.generation))
            } else {
                if (named != null && named.incarnation == incarnation && named != lastGeneration) {
                    return@withLock ReleaseResult.Refused(HostRejection.STALE_GENERATION, "请求引用的所有者代际未知", status.value)
                }
                // Hold the release against a lagging selection until a newer explicit acquire.
                held = request.id
                publishStatus()
                ReleaseResult.Released(
                    ReleaseReceipt(
                        request.id,
                        incarnation,
                        generation = named?.takeIf { it == lastGeneration },
                        predecessorEnded = named != null && named.incarnation != incarnation,
                    ),
                )
            }
        }
    }

    /** Establishes the module owner for a switch to [SwitchRequest.to] = MODULE with the user's settings. */
    suspend fun acquire(
        caller: CallerIdentity,
        leaseId: String,
        request: SwitchRequest,
        settings: HostSettings,
    ): AcquireResult {
        val lease = leaseFor(caller, leaseId)
            ?: return AcquireResult.Refused(HostRejection.INVALID_LEASE, "控制宿主授权无效或已过期")
        if (lease.role != ClientRole.APP) return AcquireResult.Refused(HostRejection.FORBIDDEN, "只有 OpenRose 应用可以请求交接")
        if (request.to != ControlWay.MODULE) return AcquireResult.Refused(HostRejection.INVALID_REQUEST, "该请求不是切换到模块控制")
        return ownership.withLock {
            if (!configuration.userUnlocked) {
                return@withLock AcquireResult.Refused(HostRejection.INVALID_REQUEST, "用户尚未解锁，模块控制不会启动")
            }
            held = null
            configuration = configuration.copy(
                settings = settings.copy(integration = settings.integration.copy(controlWay = ControlWay.MODULE)),
            )
            reconcile()
            owner.value?.let { AcquireResult.Acquired(it.generation, it.holder) }
                ?: AcquireResult.Refused(HostRejection.INVALID_REQUEST, "模块控制未能启动")
        }
    }

    private sealed interface OwnerCheck {
        class Current(val owner: Owner) : OwnerCheck
        class Rejected(val rejection: HostRejection, val message: String) : OwnerCheck
    }

    private fun currentOwner(generation: OwnerGeneration): OwnerCheck {
        val active = owner.value ?: return OwnerCheck.Rejected(HostRejection.NO_OWNER, "当前没有控制所有者")
        if (active.generation != generation) return OwnerCheck.Rejected(HostRejection.STALE_GENERATION, "所有者代际已过期")
        return OwnerCheck.Current(active)
    }

    private fun leaseFor(caller: CallerIdentity, leaseId: String): Lease? = leases.value[leaseId]?.takeIf {
        !retired.value && it.caller == caller && authority.roleOf(caller) == it.role
    }

    private suspend fun dispatch(runtime: SystemEntryRuntime, command: HostCommand): HostCallResult = when (command) {
        HostCommand.RefreshBondedDevices -> {
            runtime.refreshBondedDevices()
            HostCallResult.Operation(OperationResult.Success)
        }

        HostCommand.StartScan -> HostCallResult.Operation(runtime.startScan())

        HostCommand.StopScan -> {
            runtime.stopScan()
            HostCallResult.Operation(OperationResult.Success)
        }

        is HostCommand.Connect -> HostCallResult.Operation(runtime.connect(command.deviceId))

        is HostCommand.ConnectFromSystem -> HostCallResult.Operation(runtime.connectFromSystem(command.deviceId))

        is HostCommand.ConnectForAddDevice -> HostCallResult.Operation(runtime.connectForAddDevice(command.deviceId))

        HostCommand.Disconnect -> HostCallResult.Operation(
            if (runtime.disconnect()) {
                OperationResult.Success
            } else {
                OperationResult.Failure(ControlError(ControlErrorCode.FAILED, "无法确认控制连接已关闭"))
            },
        )

        is HostCommand.Set -> HostCallResult.Operation(
            if (command.reference == null) {
                runtime.set(command.setting, command.value)
            } else {
                runtime.set(command.reference, command.setting, command.value)
            },
        )

        is HostCommand.UpdateSettings -> {
            // A preference change never moves the owner; only an explicit handoff changes the way.
            val settings = command.settings.copy(integration = command.settings.integration.copy(controlWay = ControlWay.MODULE))
            ownership.withLock {
                configuration = configuration.copy(settings = settings)
                owner.value?.runtime?.let {
                    it.updateSettings(settings.control)
                    it.updateIntegrationSettings(settings.integration)
                }
            }
            HostCallResult.Operation(OperationResult.Success)
        }

        is HostCommand.ClaimPrompt -> HostCallResult.Flag(runtime.claimPrompt(command.reference, command.promptId))
    }

    /** Caller holds [ownership]. */
    private suspend fun reconcile() {
        // An owner whose close was refused earlier is closed again before anything else is decided.
        owner.value?.takeIf { it.job.isCancelled }?.let {
            if (!stop(it)) {
                publishStatus()
                return
            }
        }
        val wanted = !stopped &&
            configuration.userUnlocked &&
            configuration.settings.integration.controlWay == ControlWay.MODULE &&
            held == null
        val current = owner.value
        when {
            wanted && current == null -> activate()

            !wanted && current != null -> stop(current)

            current != null -> {
                current.runtime.updateSettings(configuration.settings.control)
                current.runtime.updateIntegrationSettings(configuration.settings.integration)
            }
        }
        publishStatus()
    }

    /** Caller holds [ownership]. */
    private fun activate() {
        sequence += 1
        val generation = OwnerGeneration(incarnation, sequence)
        val job = SupervisorJob(hostContext[Job])
        val scope = CoroutineScope(hostContext + job)
        val runtime = SystemEntryRuntime(
            runtimeFactory(scope, configuration.settings.control, platform, SessionIdBase.module(sequence)),
            scope,
            presentationTiming,
            configuration.settings.integration,
        )
        val holder = OwnerHolder(ControlWay.MODULE, generation, packageName, processName, uid, pid)
        owner.value = Owner(generation, holder, job, runtime)
    }

    /**
     * Caller holds [ownership]. Cancels and joins everything the owner started (including commands in
     * flight), then closes the connection; only a closed connection counts as released.
     */
    private suspend fun stop(current: Owner): Boolean {
        current.job.cancelAndJoin()
        if (!current.runtime.disconnect()) return false
        lastGeneration = current.generation
        owner.value = null
        current.ended.complete(Unit)
        return true
    }

    private fun publishStatus() {
        val current = owner.value
        mutableStatus.value = if (current != null) {
            HostStatus.Active(current.generation, current.holder)
        } else {
            HostStatus.Inactive(
                incarnation,
                lastGeneration,
                when {
                    !configuration.userUnlocked -> "用户尚未解锁"
                    stopped -> "宿主已停止"
                    held != null -> "已按切换请求释放"
                    configuration.settings.integration.controlWay != ControlWay.MODULE -> "当前未选择模块控制"
                    else -> "模块控制尚未启动"
                },
            )
        }
    }
}

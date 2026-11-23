package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.HostPlatformFacts
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.UserSettings
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

data class HostConfiguration(
    val userUnlocked: Boolean = false,
    val settings: HostSettings = HostSettings(),
)

sealed interface HostReadResult {
    data class Available(val snapshot: SessionSnapshotResult) : HostReadResult
    data class Rejected(val rejection: HostRejection, val message: String) : HostReadResult
}

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
    private val onOwnerStarted: (OwnerGeneration, CoroutineScope, ControlRuntime) -> Unit = { _, _, _ -> },
) {
    private class Owner(
        val generation: OwnerGeneration,
        val holder: OwnerHolder,
        val job: Job,
        val runtime: SystemEntryRuntime,
        val ended: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        val commands = MutableStateFlow<Job>(SupervisorJob(job))
        var releasePending = false
    }

    private sealed interface OwnerCheck {
        class Current(val owner: Owner) : OwnerCheck
        class Rejected(val rejection: HostRejection, val message: String) : OwnerCheck
    }

    private class Lease(val id: String, val role: ClientRole, val caller: CallerIdentity, val releaseEpoch: Long)

    private val hostContext = parentScope.coroutineContext
    private val ownership = Mutex()
    private val lifecycle = OwnerLifecycle()
    private val owner = MutableStateFlow<Owner?>(null)
    private val leases = MutableStateFlow<Map<String, Lease>>(emptyMap())
    private var readConfiguration: () -> HostConfiguration? = { HostConfiguration() }
    private var configuration = HostConfiguration()
    private val releaseEpoch = MutableStateFlow(0L)
    private var platform = PlatformState(PermissionState.NOT_REQUIRED)
    private var held: String? = null
    private var stopped = false
    private val retired = MutableStateFlow(false)
    private var lastGeneration: OwnerGeneration? = null
    private var sequence = 0L
    private val mutableStatus = MutableStateFlow<HostStatus>(HostStatus.Inactive(incarnation, null, "尚未配置"))

    val status: StateFlow<HostStatus> = mutableStatus.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val ownerView: Flow<OwnerView?> = owner.flatMapLatest { active ->
        active?.let { current -> current.runtime.views(current.generation) } ?: flowOf(null)
    }

    suspend fun configure(read: () -> HostConfiguration?): Boolean = ownership.withLock {
        readConfiguration = read
        if (!reloadConfiguration()) return@withLock false
        lifecycle.reconcile()
        true
    }

    private fun reloadConfiguration(): Boolean {
        val previous = configuration.settings.integration.controlWay
        configuration = readConfiguration() ?: return false
        if (previous == ControlWay.ORDINARY && configuration.settings.integration.controlWay == ControlWay.MODULE) held = null
        return true
    }

    suspend fun updatePlatform(next: PlatformState) = ownership.withLock {
        platform = next
        owner.value?.runtime?.updatePlatformState(next)
    }

    suspend fun stop(): Boolean = ownership.withLock {
        stopped = true
        var released = false
        try {
            lifecycle.reconcile()
            released = owner.value == null
        } finally {
            if (released) retired.value = true else stopped = false
            lifecycle.publishStatus()
        }
        released
    }

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
                current + (id to Lease(id, role, caller, releaseEpoch.value))
            }
        }
        if (refused) return HostOpenResult.Refused(HostRejection.INVALID_REQUEST, "控制宿主连接数已满")
        return HostOpenResult.Opened(HostLease(id, role, incarnation), status.value)
    }

    fun close(caller: CallerIdentity, leaseId: String) {
        leases.update { current -> current[leaseId]?.takeIf { it.caller == caller }?.let { current - leaseId } ?: current }
    }

    val control = ClientControl()

    inner class ClientControl internal constructor() {
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
            val commands = active.commands.value
            return try {
                withContext(commands) { dispatch(active.runtime, systemRouted(lease.role, active.runtime, command)) }
            } catch (e: CancellationException) {
                if (commands.isCancelled) HostCallResult.Rejected(HostRejection.NO_OWNER, "控制所有者已释放") else throw e
            }
        }

        private fun systemRouted(role: ClientRole, runtime: SystemEntryRuntime, command: HostCommand): HostCommand {
            if (role != ClientRole.MILINK && role != ClientRole.XIAOMI_BLUETOOTH) return command
            if (command !is HostCommand.Set || command.reference == null) return command
            val requested = (command.command as? HeadsetCommand.NoiseMode)?.mode?.id ?: return command
            if (!runtime.settings.fusionOffAsWind) return command
            val modes = (runtime.readSession(command.reference) as? SessionSnapshotResult.Available)
                ?.device?.capabilities?.noiseControl?.modes ?: return command
            val mode = SystemHeadsetMapping.requestedMode(requested, offAsWind = true, supportedModes = modes)
            if (mode == requested) return command
            return command.copy(command = HeadsetCommand.NoiseMode(requireNotNull(NoiseMode.fromId(mode))))
        }

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

        fun view(caller: CallerIdentity, leaseId: String): OwnerView? {
            leaseFor(caller, leaseId) ?: return null
            val active = owner.value ?: return null
            return active.runtime.view(active.generation)
        }

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
                            HostPlatformFacts(
                                view.state.platform.permission,
                                view.state.platform.transport,
                                view.state.platform.scanPermission,
                            ),
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

        private fun currentOwner(generation: OwnerGeneration): OwnerCheck {
            val active = owner.value ?: return OwnerCheck.Rejected(HostRejection.NO_OWNER, "当前没有控制所有者")
            if (active.generation != generation) return OwnerCheck.Rejected(HostRejection.STALE_GENERATION, "所有者代际已过期")
            return OwnerCheck.Current(active)
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
                    runtime.set(command.command)
                } else {
                    runtime.set(command.reference, command.command)
                },
            )

            is HostCommand.ClaimPrompt -> HostCallResult.Flag(runtime.claimPrompt(command.reference, command.promptId))
        }
    }

    suspend fun release(caller: CallerIdentity, leaseId: String, request: SwitchRequest): ReleaseResult {
        val lease = leaseFor(caller, leaseId)
            ?: return ReleaseResult.Refused(HostRejection.INVALID_LEASE, "控制宿主授权无效或已过期")
        if (lease.role != ClientRole.APP) return ReleaseResult.Refused(HostRejection.FORBIDDEN, "只有 OpenRose 应用可以请求交接")
        return ownership.withLock {
            val current = owner.value
            val named = request.fromGeneration
            if (current != null) {
                if (named != current.generation) {
                    return@withLock ReleaseResult.Refused(
                        HostRejection.STALE_GENERATION,
                        if (named == null) "请求未指明所有者代际" else "请求引用的所有者代际已过期",
                        status.value,
                    )
                }
                held = request.id
                if (!lifecycle.stop(current)) {
                    held = null
                    return@withLock ReleaseResult.Refused(HostRejection.RELEASE_FAILED, "无法确认控制连接已关闭", status.value)
                }
                confirmRelease(request.id)
                ReleaseResult.Released(ReleaseReceipt(request.id, incarnation, current.generation))
            } else {
                if (named != null && named.incarnation == incarnation && named != lastGeneration) {
                    return@withLock ReleaseResult.Refused(HostRejection.STALE_GENERATION, "请求引用的所有者代际未知", status.value)
                }
                confirmRelease(request.id)
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

    private fun confirmRelease(requestId: String) {
        reloadConfiguration()
        held = requestId
        releaseEpoch.value++
        lifecycle.publishStatus()
    }

    suspend fun acquire(caller: CallerIdentity, leaseId: String, request: SwitchRequest): AcquireResult = ownership.withLock {
        val lease = leaseFor(caller, leaseId)
        if (lease == null || lease.releaseEpoch != releaseEpoch.value) {
            return@withLock AcquireResult.Refused(HostRejection.INVALID_LEASE, "交接授权已失效，请重新发现宿主")
        }
        if (lease.role != ClientRole.APP) return@withLock AcquireResult.Refused(HostRejection.FORBIDDEN, "只有 OpenRose 应用可以请求交接")
        if (request.to != ControlWay.MODULE) return@withLock AcquireResult.Refused(HostRejection.INVALID_REQUEST, "该请求不是切换到模块控制")
        if (!reloadConfiguration()) {
            return@withLock AcquireResult.Refused(HostRejection.INVALID_REQUEST, "暂时无法读取已保存的控制方式")
        }
        if (!configuration.userUnlocked || configuration.settings.integration.controlWay != ControlWay.MODULE) {
            return@withLock AcquireResult.Refused(HostRejection.INVALID_REQUEST, "用户尚未解锁或未保存模块控制")
        }
        held = null
        lifecycle.reconcile()
        owner.value?.let { AcquireResult.Acquired(it.generation, it.holder) }
            ?: AcquireResult.Refused(HostRejection.INVALID_REQUEST, "模块控制未能启动")
    }

    private fun leaseFor(caller: CallerIdentity, leaseId: String): Lease? = leases.value[leaseId]?.takeIf {
        !retired.value && it.caller == caller && authority.roleOf(caller) == it.role
    }

    private inner class OwnerLifecycle {
        suspend fun reconcile() {
            owner.value?.takeIf { it.releasePending }?.let {
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

        private fun activate() {
            sequence += 1
            val generation = OwnerGeneration(incarnation, sequence)
            val job = SupervisorJob(hostContext[Job])
            val scope = CoroutineScope(hostContext + job)
            val runtime = SystemEntryRuntime(
                runtimeFactory(scope, configuration.settings.control, platform, SessionIdBase.module(sequence)).also {
                    onOwnerStarted(generation, scope, it)
                },
                scope,
                presentationTiming,
                configuration.settings.integration,
            )
            val holder = OwnerHolder(ControlWay.MODULE, generation, packageName, processName, uid, pid)
            owner.value = Owner(generation, holder, job, runtime)
        }

        suspend fun stop(current: Owner): Boolean {
            current.commands.value.cancelAndJoin()
            if (!current.runtime.release()) {
                current.releasePending = true
                current.commands.value = SupervisorJob(current.job)
                return false
            }
            current.job.cancelAndJoin()
            lastGeneration = current.generation
            owner.value = null
            current.ended.complete(Unit)
            return true
        }

        fun publishStatus() {
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
}

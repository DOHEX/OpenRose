package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether the module owner may still hold the session. It is set when module control is selected and
 * cleared only by [ReleaseProof], so ordinary control cannot start while a module owner is unaccounted for.
 */
data class PendingModuleRelease(val requestId: String?, val generation: OwnerGeneration?)

/** The user's durable choice plus the handoff bookkeeping that must survive a restart. */
data class OwnerRecord(
    val selected: ControlWay = ControlWay.ORDINARY,
    val moduleRelease: PendingModuleRelease? = null,
)

interface OwnerSelectionStore {
    fun load(): OwnerRecord

    /** True only when the record is durable and visible to the module host; false changes nothing. */
    fun save(record: OwnerRecord): Boolean

    /** Whether [save] could succeed now, so a handoff can refuse before it closes anything. */
    fun canSave(record: OwnerRecord): Boolean = true
}

/** Who the local application owner is, for diagnostics. */
data class LocalOwnerIdentity(val packageName: String, val processName: String, val uid: Int? = null, val pid: Int? = null)

/**
 * One claim on the ordinary control session. The session exists while at least one claim is open and
 * ends, closing its control connection, when the last one is released. Releasing twice is harmless.
 */
class OrdinarySessionHold internal constructor(private val onRelease: () -> Unit) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/**
 * The runtime the application's interface uses. It routes to exactly one owner: the ordinary session
 * built in this process, or the module host reached through [moduleLink]. A switch first releases the
 * old owner (with proof) and only then establishes the new one, so there is never a second owner.
 */
class SwitchingControlRuntime(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings,
    private val store: OwnerSelectionStore,
    private val ordinaryFactory: (CoroutineScope, UserSettings, PlatformState) -> ControlRuntime,
    moduleLink: ModuleHostLink,
    private val scope: CoroutineScope,
    private val local: LocalOwnerIdentity,
    private val newId: () -> String,
    private val timing: ClientTiming = ClientTiming(),
    userUnlocked: Boolean = true,
) : ControlRuntime, ControlOwnerSwitching {
    private class Ordinary(val generation: OwnerGeneration, val job: Job, val runtime: ControlRuntime)

    private val client = ModuleControlClient(moduleLink, scope, timing)
    private val switchLock = Mutex()
    private val localIncarnation = newId()
    private var localSequence = 0L
    private var unlocked = userUnlocked
    private var record = if (userUnlocked) store.load() else OwnerRecord()
    private var settings = initialSettings.copy(controlWay = record.selected)
    private var platform = initialPlatformState
    private var ordinary: Ordinary? = null
    private var holds = 0
    private var switching: SwitchRequest? = null
    private var blocked: OwnerReadiness.SwitchBlocked? = null
    private var lastHandoff: HandoffOutcome? = null
    private var releaseRetry: Job? = null
    private val mutableState = MutableStateFlow(compose())

    override val state: StateFlow<ControlState> = mutableState.asStateFlow()

    init {
        scope.launch { client.view.collect { refresh() } }
        if (unlocked) startup()
        refresh()
    }

    /**
     * Claims the ordinary session for the caller: the visible interface and the background-control
     * service each take one. The session is created by a claim only while ordinary control is the saved
     * choice and the user has unlocked, and ends when the last claim is released, so a cached process
     * with no interface and no service keeps no control connection. Module control never creates it.
     * Call from the runtime's dispatcher like every other member.
     */
    fun holdOrdinarySession(): OrdinarySessionHold {
        holds += 1
        startOrdinary()
        refresh()
        return OrdinarySessionHold(::releaseHold)
    }

    private fun releaseHold() {
        holds -= 1
        refresh()
        if (holds > 0) return
        scope.launch {
            switchLock.withLock {
                // A claim taken again before this ran keeps the session; a failed close leaves it as a handoff would.
                if (holds == 0) ordinary?.let { stopOrdinary(it) }
                // A claim taken while the session was closing found it still present and could not start one.
                startOrdinary()
            }
        }
    }

    /**
     * Credential-protected preferences exist only after first unlock; until then nothing owns the session.
     * [persisted] are the user's stored settings, whose saved control way decides which owner starts.
     */
    fun onUserUnlocked(persisted: UserSettings) {
        if (unlocked) return
        unlocked = true
        record = store.load()
        settings = persisted.copy(controlWay = record.selected)
        startup()
        refresh()
    }

    private fun startup() {
        when {
            record.selected == ControlWay.ORDINARY && record.moduleRelease == null -> startOrdinary()
            record.selected != ControlWay.MODULE && record.moduleRelease != null -> resumeModuleRelease()
            record.selected == ControlWay.MODULE -> client.start()
        }
    }

    override suspend fun selectControlWay(way: ControlWay): HandoffOutcome = switchLock.withLock {
        if (!unlocked) {
            val request = SwitchRequest(newId(), record.selected, way)
            return@withLock HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "用户解锁前无法保存或切换控制方式")
        }
        switchTo(way)
    }

    override suspend fun disableModuleControl(): HandoffOutcome = switchLock.withLock {
        if (record.selected != ControlWay.MODULE) {
            HandoffOutcome.Completed(SwitchRequest(newId(), record.selected, record.selected, record.moduleRelease?.generation))
        } else switchTo(ControlWay.NONE)
    }

    private suspend fun switchTo(target: ControlWay): HandoffOutcome {
        val previous = record
        val request = SwitchRequest(newId(), previous.selected, target, previous.moduleRelease?.generation ?: client.lastObservedGeneration)
        releaseRetry?.cancelAndJoin()
        releaseRetry = null
        switching = request
        blocked = null
        refresh()
        val next = OwnerRecord(
            selected = target,
            moduleRelease = if (target == ControlWay.MODULE) previous.moduleRelease ?: PendingModuleRelease(request.id, null) else previous.moduleRelease,
        )
        val saves = next != previous
        if (saves && !store.canSave(next)) {
            return finish(HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "无法保存控制方式，未做任何切换"))
        }
        // Saving module control tells the module host, which then connects by itself. So while an ordinary
        // connection is open it is closed first; in every other direction the intent is made durable first,
        // so a restarted module host never reclaims what the user left.
        val closeFirst = target == ControlWay.MODULE && ordinary != null
        if (closeFirst) closeOrdinary(request)?.let { return it }
        if (saves && !store.save(next)) {
            if (closeFirst) startOrdinary()
            return finish(HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "无法保存控制方式，未做任何切换"))
        }
        record = next
        settings = settings.copy(controlWay = target)
        closeOrdinary(request)?.let { return it }
        return when (target) {
            ControlWay.MODULE -> establishModule(request)
            ControlWay.ORDINARY -> returnToOrdinary(request)
            ControlWay.NONE -> disableModule(request)
        }
    }

    private suspend fun establishModule(request: SwitchRequest): HandoffOutcome {
        client.start()
        return when (val outcome = client.acquire(request, settings)) {
            is AcquireOutcome.Acquired -> {
                record = record.copy(moduleRelease = PendingModuleRelease(request.id, outcome.generation))
                store.save(record)
                finish(HandoffOutcome.Completed(request))
            }
            // The choice stays module control; the client keeps trying and nothing else takes over.
            is AcquireOutcome.Failed -> finish(HandoffOutcome.Blocked(request, outcome.stage, outcome.reason))
        }
    }

    private suspend fun returnToOrdinary(request: SwitchRequest): HandoffOutcome {
        client.stop()
        if (record.moduleRelease != null) {
            when (val proof = client.release(request.copy(fromGeneration = record.moduleRelease?.generation ?: request.fromGeneration))) {
                is ReleaseProof.Unproven -> {
                    releaseRetry = scope.launch { retryModuleRelease() }
                    return finish(HandoffOutcome.Blocked(request, proof.stage, proof.reason))
                }
                is ReleaseProof.Receipt, is ReleaseProof.ProcessEnded -> clearModuleRelease()
            }
        }
        startOrdinary()
        return finish(HandoffOutcome.Completed(request))
    }

    private suspend fun disableModule(request: SwitchRequest): HandoffOutcome {
        if (record.moduleRelease != null) {
            when (val proof = client.release(request.copy(fromGeneration = record.moduleRelease?.generation ?: request.fromGeneration))) {
                is ReleaseProof.Unproven -> {
                    // The disable stays saved so the host never reclaims, but it is not done while the module owner
                    // may still hold the connection: keep asking until proven, and never start ordinary control.
                    client.stop()
                    releaseRetry = scope.launch { retryModuleRelease() }
                    return finish(HandoffOutcome.Blocked(request, proof.stage, proof.reason))
                }
                is ReleaseProof.Receipt, is ReleaseProof.ProcessEnded -> clearModuleRelease()
            }
        }
        client.stop()
        return finish(HandoffOutcome.Completed(request))
    }

    private fun clearModuleRelease() {
        record = record.copy(moduleRelease = null)
        // If saving fails the proof still holds now; after a restart the host simply confirms it again.
        store.save(record)
    }

    /** Ordinary control stays blocked until the module owner is proven released; keeps asking while module control is not selected. */
    private fun resumeModuleRelease() {
        val request = SwitchRequest(newId(), ControlWay.MODULE, record.selected, record.moduleRelease?.generation)
        blocked = OwnerReadiness.SwitchBlocked(request, OwnerFailureStage.RELEASE, "尚未确认模块已释放控制连接")
        releaseRetry = scope.launch { retryModuleRelease() }
    }

    /** The owner the host last reported is the one a release must name; it is recorded, then asked for explicitly. */
    private fun adoptObservedOwner() {
        val observed = client.lastObservedGeneration ?: return
        val pending = record.moduleRelease ?: return
        if (pending.generation == observed) return
        record = record.copy(moduleRelease = pending.copy(generation = observed))
        store.save(record)
    }

    private suspend fun retryModuleRelease() {
        while (scope.isActive && record.selected != ControlWay.MODULE && record.moduleRelease != null) {
            adoptObservedOwner()
            val request = SwitchRequest(newId(), ControlWay.MODULE, record.selected, record.moduleRelease?.generation)
            when (val proof = client.release(request)) {
                is ReleaseProof.Unproven -> {
                    blocked = OwnerReadiness.SwitchBlocked(request, proof.stage, proof.reason)
                    refresh()
                    delay(timing.retryMillis)
                }
                is ReleaseProof.Receipt, is ReleaseProof.ProcessEnded -> {
                    switchLock.withLock {
                        if (record.selected != ControlWay.MODULE) {
                            clearModuleRelease()
                            blocked = null
                            lastHandoff = HandoffOutcome.Completed(request)
                            startOrdinary()
                        }
                    }
                    refresh()
                    return
                }
            }
        }
    }

    private fun finish(outcome: HandoffOutcome): HandoffOutcome {
        lastHandoff = outcome
        switching = null
        blocked = (outcome as? HandoffOutcome.Blocked)?.let { OwnerReadiness.SwitchBlocked(it.request, it.stage, it.reason) }
        refresh()
        return outcome
    }

    private fun startOrdinary() {
        if (ordinary != null || holds == 0 || !unlocked) return
        if (record.selected != ControlWay.ORDINARY || record.moduleRelease != null) return
        localSequence += 1
        val generation = OwnerGeneration(localIncarnation, localSequence)
        val job = SupervisorJob(scope.coroutineContext[Job])
        val runtime = ordinaryFactory(CoroutineScope(scope.coroutineContext + job), settings, platform)
        ordinary = Ordinary(generation, job, runtime)
        scope.launch(job) { runtime.state.collect { refresh() } }
    }

    /** Cancels and joins everything the ordinary owner started, then closes its connection; false if the close is unconfirmed. */
    private suspend fun stopOrdinary(current: Ordinary): Boolean {
        current.job.cancelAndJoin()
        if (!current.runtime.disconnect()) return false
        ordinary = null
        refresh()
        return true
    }

    /** Null once no ordinary connection remains; otherwise the handoff is blocked because its close was not confirmed. */
    private suspend fun closeOrdinary(request: SwitchRequest): HandoffOutcome? {
        val current = ordinary ?: return null
        return if (stopOrdinary(current)) null
        else finish(HandoffOutcome.Blocked(request, OwnerFailureStage.RELEASE, "普通控制连接未能确认关闭"))
    }

    private fun refresh() {
        mutableState.value = compose()
    }

    private fun ownerState(view: ModuleView): OwnerState {
        val selected = record.selected
        val localHolder = ordinary?.let {
            OwnerHolder(ControlWay.ORDINARY, it.generation, local.packageName, local.processName, local.uid, local.pid)
        }
        val moduleHolder = (view as? ModuleView.Active)?.frame?.holder
        // While module control is the saved choice, the live link view is more current than an earlier acquire failure.
        val activeBlock = blocked?.takeUnless { selected == ControlWay.MODULE && it.request.to == ControlWay.MODULE }
        val readiness = if (!unlocked) OwnerReadiness.Idle("用户解锁后才会控制耳机") else
            switching?.let { OwnerReadiness.Switching(it) } ?: activeBlock ?: when (selected) {
            ControlWay.ORDINARY -> when {
                ordinary != null -> OwnerReadiness.Ready
                holds == 0 -> OwnerReadiness.Idle("没有界面或后台控制服务需要控制会话")
                else -> OwnerReadiness.Starting
            }
            ControlWay.MODULE -> when (view) {
                is ModuleView.Active -> OwnerReadiness.Ready
                ModuleView.Stopped, ModuleView.Connecting -> OwnerReadiness.Starting
                is ModuleView.Unavailable -> OwnerReadiness.Unavailable(view.stage, view.reason)
                is ModuleView.Inactive -> OwnerReadiness.Unavailable(OwnerFailureStage.ACTIVATION, view.status.reason)
            }
            ControlWay.NONE -> OwnerReadiness.Idle("模块控制已停用")
        }
        return OwnerState(selected, readiness, if (selected == ControlWay.MODULE) moduleHolder else localHolder, lastHandoff)
    }

    private fun compose(): ControlState {
        val view = client.view.value
        val owner = ownerState(view)
        val local = ordinary
        // While module control is chosen the platform's module/bridge facts are the live host link, not a guess.
        val modulePlatform = if (record.selected == ControlWay.MODULE) moduleFacts(platform, view) else platform
        return when {
            local != null -> local.runtime.state.value.copy(platform = platform, settings = settings, owner = owner)
            record.selected == ControlWay.MODULE && view is ModuleView.Active -> {
                val frame = view.frame
                frame.state.copy(
                    platform = modulePlatform.copy(permission = frame.platform.permission, transport = frame.platform.transport),
                    settings = settings,
                    owner = owner,
                )
            }
            else -> ControlState(platform = modulePlatform, settings = settings, owner = owner)
        }
    }

    private fun moduleFacts(base: PlatformState, view: ModuleView): PlatformState = when (view) {
        is ModuleView.Active -> base.copy(
            module = ModuleAvailability.Available,
            bridge = SystemBridgeDiagnostics(
                targetPackage = view.frame.holder.packageName, targetProcess = view.frame.holder.processName,
                ipc = BridgeIpcState.AUTHENTICATED,
                session = if (view.frame.state.device != null) BridgeSessionState.READY else BridgeSessionState.NONE,
                detail = "模块控制所有者 ${view.frame.generation}",
                presentation = view.frame.facts.presentation, endpoints = view.frame.facts.endpoints,
            ),
        )
        is ModuleView.Inactive -> base.copy(
            module = ModuleAvailability.Unavailable(view.status.reason),
            bridge = SystemBridgeDiagnostics(ipc = BridgeIpcState.AUTHENTICATED, detail = view.status.reason),
        )
        is ModuleView.Unavailable -> base.copy(
            module = ModuleAvailability.Unavailable(view.reason),
            bridge = SystemBridgeDiagnostics(
                ipc = if (view.stage == OwnerFailureStage.AUTHORIZATION) BridgeIpcState.REJECTED else BridgeIpcState.DISCONNECTED,
                detail = "${view.stage}: ${view.reason}",
            ),
        )
        ModuleView.Connecting, ModuleView.Stopped -> base.copy(
            module = ModuleAvailability.Unavailable("正在连接模块控制宿主"),
            bridge = SystemBridgeDiagnostics(ipc = BridgeIpcState.WAITING, detail = "正在连接模块控制宿主"),
        )
    }

    private fun noOwner() = OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, "当前没有就绪的控制所有者"))

    private suspend fun module(command: HostCommand): OperationResult = when (val result = client.execute(command)) {
        is HostCallResult.Operation -> result.result
        is HostCallResult.Flag -> OperationResult.Success
        is HostCallResult.Rejected -> OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, result.message))
    }

    private suspend fun route(
        ordinaryCall: suspend (ControlRuntime) -> OperationResult,
        command: HostCommand,
    ): OperationResult {
        val local = ordinary
        return when {
            local != null -> ordinaryCall(local.runtime)
            record.selected == ControlWay.MODULE -> module(command)
            else -> noOwner()
        }
    }

    override suspend fun refreshBondedDevices() {
        ordinary?.let { it.runtime.refreshBondedDevices(); return }
        if (record.selected == ControlWay.MODULE) client.execute(HostCommand.RefreshBondedDevices)
    }

    override suspend fun startScan() = route({ it.startScan() }, HostCommand.StartScan)

    override suspend fun stopScan() {
        route({ it.stopScan(); OperationResult.Success }, HostCommand.StopScan)
    }

    override suspend fun connect(deviceId: DeviceId) = route({ it.connect(deviceId) }, HostCommand.Connect(deviceId))

    override suspend fun connectFromSystem(deviceId: DeviceId) =
        route({ it.connectFromSystem(deviceId) }, HostCommand.ConnectFromSystem(deviceId))

    override suspend fun connectForAddDevice(deviceId: DeviceId) =
        route({ it.connectForAddDevice(deviceId) }, HostCommand.ConnectForAddDevice(deviceId))

    override suspend fun disconnect(): Boolean {
        ordinary?.let { return it.runtime.disconnect() }
        // With no owner there is no control connection to close.
        return record.selected != ControlWay.MODULE || module(HostCommand.Disconnect) == OperationResult.Success
    }

    override suspend fun set(setting: DeviceSetting, value: FieldValue) =
        route({ it.set(setting, value) }, HostCommand.Set(null, setting, value))

    override suspend fun set(reference: SessionReference, setting: DeviceSetting, value: FieldValue) =
        route({ it.set(reference, setting, value) }, HostCommand.Set(reference, setting, value))

    override fun readSession(reference: SessionReference): SessionSnapshotResult {
        ordinary?.let { return it.runtime.readSession(reference) }
        val device = (client.view.value as? ModuleView.Active)?.frame?.state?.device
        val connected = (client.view.value as? ModuleView.Active)?.frame?.state?.connection as? ConnectionState.Connected
        return if (device != null && connected != null && device.deviceId == reference.deviceId && device.sessionId == reference.sessionId) {
            SessionSnapshotResult.Available(device)
        } else {
            SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "设备会话已结束或尚未就绪"))
        }
    }

    override suspend fun claimConnectionPrompt(reference: SessionReference, promptId: String): Boolean {
        ordinary?.let { return it.runtime.claimConnectionPrompt(reference, promptId) }
        return (client.execute(HostCommand.ClaimPrompt(reference, promptId)) as? HostCallResult.Flag)?.value == true
    }

    override fun updateSettings(settings: UserSettings) {
        // A preference change never moves the owner; only an explicit handoff changes the control way.
        val normalized = settings.copy(controlWay = record.selected)
        this.settings = normalized
        ordinary?.runtime?.updateSettings(normalized)
        if (record.selected == ControlWay.MODULE) scope.launch { client.execute(HostCommand.UpdateSettings(normalized)) }
        refresh()
    }

    override fun updatePlatformState(platformState: PlatformState) {
        platform = platformState
        ordinary?.runtime?.updatePlatformState(platformState)
        refresh()
    }
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.UserSettings
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
 * [state] is the owner's control state; [integration] says who that owner is, how the system module
 * stands and what the current owner's system presentation shows.
 */
class SwitchingControlRuntime(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings,
    private val store: OwnerSelectionStore,
    private val ordinaryFactory: (CoroutineScope, UserSettings, PlatformState, Long) -> ControlRuntime,
    moduleLink: ModuleHostLink,
    private val scope: CoroutineScope,
    private val local: LocalOwnerIdentity,
    private val newId: () -> String,
    private val timing: ClientTiming = ClientTiming(),
    userUnlocked: Boolean = true,
    initialIntegrationPlatform: IntegrationPlatformState = IntegrationPlatformState(),
    initialIntegrationSettings: IntegrationSettings = IntegrationSettings(),
    private val presentationTiming: PresentationTiming = PresentationTiming(),
) : ControlRuntime,
    ControlOwnerSwitching,
    SystemEntry {
    private class Ordinary(val generation: OwnerGeneration, val job: Job, val runtime: SystemEntryRuntime)

    private val client = ModuleControlClient(moduleLink, scope, timing)
    private val switchLock = Mutex()
    private val localIncarnation = newId()
    private var localSequence = 0L
    private var unlocked = userUnlocked
    private var record = if (userUnlocked) store.load() else OwnerRecord()
    private var settings = initialSettings
    private var integrationSettings = initialIntegrationSettings.copy(controlWay = record.selected)
    private var platform = initialPlatformState
    private var integrationPlatform = initialIntegrationPlatform
    private var ordinary: Ordinary? = null
    private val servingOrdinary: Ordinary? get() = ordinary?.takeIf { holds > 0 && it.job.isActive }
    private var holds = 0
    private var switching: SwitchRequest? = null
    private var blocked: OwnerReadiness.SwitchBlocked? = null
    private var lastHandoff: HandoffOutcome? = null
    private var releaseRetry: Job? = null
    private val mutableState = MutableStateFlow(composeControl())
    private val mutableIntegration = MutableStateFlow(composeIntegration())

    override val state: StateFlow<ControlState> = mutableState.asStateFlow()
    val integration: StateFlow<IntegrationState> = mutableIntegration.asStateFlow()

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
            val retiring = switchLock.withLock {
                ordinary?.takeIf { holds == 0 && it.job.isActive }?.also { it.job.cancelAndJoin() }
            } ?: return@launch
            // A cached process must not react to audio while the platform refuses to close its last handle.
            while (!finishIdleRelease(retiring)) delay(timing.retryMillis)
        }
    }

    private suspend fun finishIdleRelease(retiring: Ordinary): Boolean = switchLock.withLock {
        if (ordinary !== retiring) return@withLock true
        val closed = stopOrdinary(retiring)
        if (closed) startOrdinary() // A new claim may have arrived while the old handle was closing.
        refresh()
        closed
    }

    /**
     * Credential-protected preferences exist only after first unlock; until then nothing owns the session.
     * [persisted] are the user's stored settings, whose saved control way decides which owner starts.
     */
    fun onUserUnlocked(persisted: HostSettings) {
        if (unlocked) return
        unlocked = true
        record = store.load()
        settings = persisted.control
        integrationSettings = persisted.integration.copy(controlWay = record.selected)
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
            moduleRelease = if (target == ControlWay.MODULE) {
                previous.moduleRelease ?: PendingModuleRelease(request.id, null)
            } else {
                previous.moduleRelease
            },
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
        integrationSettings = integrationSettings.copy(controlWay = target)
        closeOrdinary(request)?.let { return it }
        return when (target) {
            ControlWay.MODULE -> establishModule(request)
            ControlWay.ORDINARY -> returnToOrdinary(request)
        }
    }

    private suspend fun establishModule(request: SwitchRequest): HandoffOutcome {
        client.start()
        return when (val outcome = client.acquire(request)) {
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
        val ownerScope = CoroutineScope(scope.coroutineContext + job)
        val runtime = SystemEntryRuntime(
            ordinaryFactory(ownerScope, settings, platform, SessionIdBase.ordinary(localSequence)),
            ownerScope,
            presentationTiming,
            integrationSettings,
        )
        ordinary = Ordinary(generation, job, runtime)
        ownerScope.launch { runtime.state.collect { refresh() } }
        ownerScope.launch { runtime.presentation.collect { refresh() } }
    }

    /** A failed handoff leaves the held runtime usable. A successful release prohibits any later connection on it. */
    private suspend fun stopOrdinary(current: Ordinary): Boolean {
        if (!current.runtime.release()) return false
        current.job.cancelAndJoin()
        ordinary = null
        refresh()
        return true
    }

    /** Null once no ordinary connection remains; otherwise the handoff is blocked because its close was not confirmed. */
    private suspend fun closeOrdinary(request: SwitchRequest): HandoffOutcome? {
        val current = ordinary ?: return null
        return if (stopOrdinary(current)) {
            null
        } else {
            finish(HandoffOutcome.Blocked(request, OwnerFailureStage.RELEASE, "普通控制连接未能确认关闭"))
        }
    }

    private fun refresh() {
        mutableState.value = composeControl()
        mutableIntegration.value = composeIntegration()
    }

    private fun ownerState(view: ModuleView): OwnerState {
        val selected = record.selected
        val localHolder = ordinary?.let {
            OwnerHolder(ControlWay.ORDINARY, it.generation, local.packageName, local.processName, local.uid, local.pid)
        }
        val moduleHolder = (view as? ModuleView.Active)?.frame?.holder
        // While module control is the saved choice, the live link view is more current than an earlier acquire failure.
        val activeBlock = blocked?.takeUnless { selected == ControlWay.MODULE && it.request.to == ControlWay.MODULE }
        val readiness = if (!unlocked) {
            OwnerReadiness.Idle("用户解锁后才会控制耳机")
        } else {
            switching?.let { OwnerReadiness.Switching(it) } ?: activeBlock ?: when (selected) {
                ControlWay.ORDINARY -> when {
                    ordinary?.job?.isCancelled == true -> OwnerReadiness.Unavailable(OwnerFailureStage.RELEASE, "正在等待普通控制连接释放")
                    servingOrdinary != null -> OwnerReadiness.Ready
                    holds == 0 -> OwnerReadiness.Idle("没有界面或后台控制服务需要控制会话")
                    else -> OwnerReadiness.Starting
                }

                ControlWay.MODULE -> when (view) {
                    is ModuleView.Active -> OwnerReadiness.Ready
                    ModuleView.Stopped, ModuleView.Connecting -> OwnerReadiness.Starting
                    is ModuleView.Unavailable -> OwnerReadiness.Unavailable(view.stage, view.reason)
                    is ModuleView.Inactive -> OwnerReadiness.Unavailable(OwnerFailureStage.ACTIVATION, view.status.reason)
                    ModuleView.Unresponsive -> OwnerReadiness.Unavailable(OwnerFailureStage.CONNECTION, UNRESPONSIVE_REASON)
                }
            }
        }
        return OwnerState(selected, readiness, if (selected == ControlWay.MODULE) moduleHolder else localHolder, lastHandoff)
    }

    private fun composeControl(): ControlState {
        val view = client.view.value
        val local = servingOrdinary
        return when {
            local != null -> local.runtime.state.value.copy(platform = platform, settings = settings)

            record.selected == ControlWay.MODULE && view is ModuleView.Active -> {
                val frame = view.frame
                frame.state.copy(
                    platform = platform.copy(permission = frame.platform.permission, transport = frame.platform.transport),
                    settings = settings,
                )
            }

            else -> ControlState(platform = platform, settings = settings)
        }
    }

    private fun composeIntegration(): IntegrationState {
        val view = client.view.value
        return IntegrationState(
            settings = integrationSettings,
            // While module control is chosen the module/bridge facts are the live host link, not a guess.
            platform = if (record.selected == ControlWay.MODULE) moduleFacts(integrationPlatform, view) else integrationPlatform,
            owner = ownerState(view),
            presentation = servingOrdinary?.runtime?.presentation?.value
                ?: if (record.selected == ControlWay.MODULE && view is ModuleView.Active) {
                    view.frame.presentation
                } else {
                    SystemPresentationState()
                },
        )
    }

    private fun moduleFacts(base: IntegrationPlatformState, view: ModuleView): IntegrationPlatformState = when (view) {
        is ModuleView.Active -> base.copy(
            module = ModuleAvailability.Available,
            bridge = SystemBridgeDiagnostics(
                targetPackage = view.frame.holder.packageName,
                targetProcess = view.frame.holder.processName,
                ipc = BridgeIpcState.AUTHENTICATED,
                session = if (view.frame.state.device != null) BridgeSessionState.READY else BridgeSessionState.NONE,
                detail = "模块控制所有者 ${view.frame.generation}",
                presentation = view.frame.facts.presentation,
                endpoints = view.frame.facts.endpoints,
            ),
        )

        is ModuleView.Inactive -> base.copy(
            module = ModuleAvailability.Unavailable(view.status.reason),
            bridge = SystemBridgeDiagnostics(ipc = BridgeIpcState.AUTHENTICATED, detail = view.status.reason),
        )

        is ModuleView.Unavailable -> base.copy(
            module = ModuleAvailability.Unavailable(view.reason, view.stage.unavailableCause()),
            bridge = SystemBridgeDiagnostics(
                ipc = if (view.stage == OwnerFailureStage.AUTHORIZATION) BridgeIpcState.REJECTED else BridgeIpcState.DISCONNECTED,
                detail = "${view.stage}: ${view.reason}",
            ),
        )

        ModuleView.Unresponsive -> base.copy(
            module = ModuleAvailability.Unresponsive,
            bridge = SystemBridgeDiagnostics(ipc = BridgeIpcState.AUTHENTICATED, detail = UNRESPONSIVE_REASON),
        )

        ModuleView.Connecting, ModuleView.Stopped -> base.copy(
            module = ModuleAvailability.Unavailable("正在连接模块控制宿主"),
            bridge = SystemBridgeDiagnostics(ipc = BridgeIpcState.WAITING, detail = "正在连接模块控制宿主"),
        )
    }

    private fun OwnerFailureStage.unavailableCause(): ModuleUnavailableCause? = when (this) {
        OwnerFailureStage.DISCOVERY -> ModuleUnavailableCause.HOST_NOT_FOUND

        OwnerFailureStage.AUTHORIZATION -> ModuleUnavailableCause.AUTHORIZATION_REFUSED

        OwnerFailureStage.CONNECTION -> ModuleUnavailableCause.LINK_LOST

        OwnerFailureStage.SELECTION_PERSISTENCE, OwnerFailureStage.RELEASE,
        OwnerFailureStage.ACTIVATION, OwnerFailureStage.OWNER_RUNTIME,
        -> null
    }

    private fun noOwner() = OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, "当前没有就绪的控制所有者"))

    private suspend fun module(command: HostCommand): OperationResult = when (val result = client.execute(command)) {
        is HostCallResult.Operation -> result.result
        is HostCallResult.Flag -> OperationResult.Success
        is HostCallResult.Rejected -> OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, result.message))
    }

    private suspend fun route(
        ordinaryCall: suspend (SystemEntryRuntime) -> OperationResult,
        command: HostCommand,
    ): OperationResult {
        val local = servingOrdinary
        return when {
            local != null -> ordinaryCall(local.runtime)
            record.selected == ControlWay.MODULE -> module(command)
            else -> noOwner()
        }
    }

    override suspend fun refreshBondedDevices() {
        servingOrdinary?.let {
            it.runtime.refreshBondedDevices()
            return
        }
        if (record.selected == ControlWay.MODULE) client.execute(HostCommand.RefreshBondedDevices)
    }

    override suspend fun startScan() = route({ it.startScan() }, HostCommand.StartScan)

    override suspend fun stopScan() {
        route({
            it.stopScan()
            OperationResult.Success
        }, HostCommand.StopScan)
    }

    override suspend fun connect(deviceId: DeviceId, pinned: Boolean) = route(
        { it.connect(deviceId, pinned) },
        if (pinned) HostCommand.ConnectFromSystem(deviceId) else HostCommand.Connect(deviceId),
    )

    override suspend fun connectFromSystem(deviceId: DeviceId) = connect(deviceId, pinned = true)

    override suspend fun connectForAddDevice(deviceId: DeviceId) = route(
        { it.connectForAddDevice(deviceId) },
        HostCommand.ConnectForAddDevice(deviceId),
    )

    override suspend fun disconnect(): Boolean {
        ordinary?.let { return it.runtime.disconnect() }
        return if (record.selected == ControlWay.MODULE) {
            module(HostCommand.Disconnect) == OperationResult.Success
        } else {
            record.moduleRelease == null
        }
    }

    override suspend fun set(setting: DeviceSetting, value: FieldValue, expectedSessionId: Long?): OperationResult {
        if (expectedSessionId == null) {
            return route({ it.set(setting, value) }, HostCommand.Set(null, setting, value))
        }
        val reference = state.value.readySession?.takeIf { it.sessionId == expectedSessionId }
            ?: return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
        return set(reference, setting, value)
    }

    override suspend fun set(reference: SessionReference, setting: DeviceSetting, value: FieldValue) = route(
        { it.set(reference, setting, value) },
        HostCommand.Set(reference, setting, value),
    )

    override fun readSession(reference: SessionReference): SessionSnapshotResult {
        servingOrdinary?.let { return it.runtime.readSession(reference) }
        val frame = (client.view.value as? ModuleView.Active)?.frame
        return if (record.selected == ControlWay.MODULE && frame?.state?.readySession == reference) {
            SessionSnapshotResult.Available(requireNotNull(frame.state.device), frame.presentation)
        } else {
            SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "设备会话已结束或尚未就绪"))
        }
    }

    override suspend fun claimPrompt(reference: SessionReference, promptId: String): Boolean {
        servingOrdinary?.let { return it.runtime.claimPrompt(reference, promptId) }
        return record.selected == ControlWay.MODULE &&
            (client.execute(HostCommand.ClaimPrompt(reference, promptId)) as? HostCallResult.Flag)?.value == true
    }

    override fun updateSettings(settings: UserSettings) {
        this.settings = settings
        ordinary?.runtime?.updateSettings(settings)
        refresh()
    }

    fun updateIntegrationSettings(settings: IntegrationSettings) {
        integrationSettings = settings.copy(controlWay = record.selected)
        ordinary?.runtime?.updateIntegrationSettings(integrationSettings)
        refresh()
    }

    override fun updatePlatformState(platformState: PlatformState) {
        platform = platformState
        ordinary?.runtime?.updatePlatformState(platformState)
        refresh()
    }

    /** Platform facts about the system module and the background service; the module ones yield to the live host link. */
    fun updateIntegrationPlatform(platformState: IntegrationPlatformState) {
        integrationPlatform = platformState
        refresh()
    }
}

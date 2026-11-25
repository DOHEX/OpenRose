package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingModuleRelease(val requestId: String?, val generation: OwnerGeneration?)

data class OwnerRecord(
    val selected: ControlWay = ControlWay.ORDINARY,
    val moduleRelease: PendingModuleRelease? = null,
)

private val OwnerRecord.awaitingModuleRelease: Boolean get() = selected == ControlWay.ORDINARY && moduleRelease != null

interface OwnerSelectionStore {
    fun load(): OwnerRecord

    fun save(record: OwnerRecord): Boolean

    suspend fun publishSelection(): Boolean

    fun canSave(record: OwnerRecord): Boolean = true
}

data class LocalOwnerIdentity(val packageName: String, val processName: String, val uid: Int? = null, val pid: Int? = null)

class OrdinarySessionHold internal constructor(private val onRelease: () -> Unit) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

class SwitchingControlRuntime private constructor(private val owners: ApplicationOwners) :
    ControlRuntime by owners.control,
    SystemEntry by owners.system,
    ControlOwnerSwitching by owners.handoff {
    constructor(
        initialPlatformState: PlatformState,
        initialSettings: UserSettings,
        store: OwnerSelectionStore,
        ordinaryFactory: (CoroutineScope, UserSettings, PlatformState, Long) -> ControlRuntime,
        moduleLink: ModuleHostLink,
        scope: CoroutineScope,
        local: LocalOwnerIdentity,
        newId: () -> String,
        timing: ClientTiming = ClientTiming(),
        userUnlocked: Boolean = true,
        initialIntegrationPlatform: IntegrationPlatformState = IntegrationPlatformState(),
        initialIntegrationSettings: IntegrationSettings = IntegrationSettings(),
        presentationTiming: PresentationTiming = PresentationTiming(),
    ) : this(
        ApplicationOwners(
            initialPlatformState,
            initialSettings,
            store,
            ordinaryFactory,
            moduleLink,
            scope,
            local,
            newId,
            timing,
            userUnlocked,
            initialIntegrationPlatform,
            initialIntegrationSettings,
            presentationTiming,
        ),
    )

    val integration get() = owners.integration
    fun holdOrdinarySession() = owners.sessions.holdOrdinarySession()
    fun onUserUnlocked(persisted: HostSettings) = owners.onUserUnlocked(persisted)
    fun updateIntegrationSettings(settings: IntegrationSettings) = owners.updateIntegrationSettings(settings)
    fun updateIntegrationPlatform(platformState: IntegrationPlatformState) = owners.updateIntegrationPlatform(platformState)
}

private class ApplicationOwners(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings,
    private val store: OwnerSelectionStore,
    private val ordinaryFactory: (CoroutineScope, UserSettings, PlatformState, Long) -> ControlRuntime,
    moduleLink: ModuleHostLink,
    private val scope: CoroutineScope,
    private val local: LocalOwnerIdentity,
    private val newId: () -> String,
    private val timing: ClientTiming,
    userUnlocked: Boolean,
    initialIntegrationPlatform: IntegrationPlatformState,
    initialIntegrationSettings: IntegrationSettings,
    private val presentationTiming: PresentationTiming,
) {
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
    private var needsSelectionPublication = false
    val sessions = OrdinarySessions()
    val handoff = Handoff()
    private val releases = ModuleRelease()
    private val projection = OwnerProjection()
    private val mutableState = MutableStateFlow(projection.composeControl())
    private val mutableIntegration = MutableStateFlow(projection.composeIntegration())

    val state: StateFlow<ControlState> = mutableState.asStateFlow()
    val integration: StateFlow<IntegrationState> = mutableIntegration.asStateFlow()

    init {
        scope.launch { client.view.collect { refresh() } }
        if (unlocked) startup()
        refresh()
    }

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
            record.selected == ControlWay.MODULE -> client.start()

            record.awaitingModuleRelease -> {
                needsSelectionPublication = true
                releases.resumeModuleRelease()
            }

            else -> sessions.startOrdinary()
        }
    }

    private fun refresh() {
        mutableState.value = projection.composeControl()
        mutableIntegration.value = projection.composeIntegration()
    }

    fun updateIntegrationSettings(settings: IntegrationSettings) {
        integrationSettings = settings.copy(controlWay = record.selected)
        ordinary?.runtime?.updateIntegrationSettings(integrationSettings)
        refresh()
    }

    fun updateIntegrationPlatform(platformState: IntegrationPlatformState) {
        integrationPlatform = platformState
        refresh()
    }

    inner class OrdinarySessions {
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
                while (!finishIdleRelease(retiring)) delay(timing.retryMillis)
            }
        }

        private suspend fun finishIdleRelease(retiring: Ordinary): Boolean = switchLock.withLock {
            if (ordinary !== retiring) return@withLock true
            val closed = stopOrdinary(retiring)
            if (closed) startOrdinary()
            refresh()
            closed
        }

        fun startOrdinary() {
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

        private suspend fun stopOrdinary(current: Ordinary): Boolean {
            if (!current.runtime.release()) return false
            current.job.cancelAndJoin()
            ordinary = null
            refresh()
            return true
        }

        suspend fun closeOrdinary(request: SwitchRequest): HandoffOutcome? {
            val current = ordinary ?: return null
            return if (stopOrdinary(current)) {
                null
            } else {
                handoff.finish(HandoffOutcome.Blocked(request, OwnerFailureStage.RELEASE, "普通控制连接未能确认关闭"))
            }
        }
    }

    inner class Handoff : ControlOwnerSwitching {
        override suspend fun selectControlWay(way: ControlWay): HandoffOutcome = switchLock.withLock {
            if (!unlocked) {
                val request = SwitchRequest(newId(), record.selected, way)
                return@withLock HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "用户解锁前无法保存或切换控制方式")
            }
            switchTo(way)
        }

        private suspend fun switchTo(target: ControlWay): HandoffOutcome {
            val previous = record
            val generation = previous.moduleRelease?.generation ?: client.lastObservedGeneration
            val request = SwitchRequest(newId(), previous.selected, target, generation)
            switching = request
            blocked = null
            refresh()
            val next = previous.forSwitch(request)
            persistSelection(request, next)?.let { return it }
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

                is AcquireOutcome.Failed -> finish(HandoffOutcome.Blocked(request, outcome.stage, outcome.reason))
            }
        }

        private suspend fun returnToOrdinary(request: SwitchRequest): HandoffOutcome {
            client.stop()
            if (record.moduleRelease != null) {
                when (val proof = releases.releaseModule(request)) {
                    is ReleaseProof.Unproven -> {
                        releaseRetry = scope.launch { releases.retryModuleRelease() }
                        return finish(HandoffOutcome.Blocked(request, proof.stage, proof.reason))
                    }

                    is ReleaseProof.Receipt, is ReleaseProof.ProcessEnded -> releases.clearModuleRelease()
                }
            }
            sessions.startOrdinary()
            return finish(HandoffOutcome.Completed(request))
        }

        fun finish(outcome: HandoffOutcome): HandoffOutcome {
            lastHandoff = outcome
            switching = null
            blocked = (outcome as? HandoffOutcome.Blocked)?.let { OwnerReadiness.SwitchBlocked(it.request, it.stage, it.reason) }
            refresh()
            return outcome
        }

        private suspend fun persistSelection(request: SwitchRequest, next: OwnerRecord): HandoffOutcome? {
            val previous = record
            val target = request.to
            val saves = next != previous
            if (saves && !store.canSave(next)) {
                return finish(HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "无法保存控制方式，未做任何切换"))
            }
            releaseRetry?.cancelAndJoin()
            releaseRetry = null
            val closeFirst = target == ControlWay.MODULE && ordinary != null
            if (closeFirst) sessions.closeOrdinary(request)?.let { return it }
            return if (saves && !store.save(next)) {
                if (closeFirst) sessions.startOrdinary()
                if (previous.awaitingModuleRelease) releases.resumeModuleRelease()
                finish(HandoffOutcome.Blocked(request, OwnerFailureStage.SELECTION_PERSISTENCE, "无法保存控制方式，未做任何切换"))
            } else {
                if (previous.selected != target) needsSelectionPublication = false
                record = next
                integrationSettings = integrationSettings.copy(controlWay = target)
                sessions.closeOrdinary(request)
            }
        }
    }

    private inner class ModuleRelease {
        suspend fun releaseModule(request: SwitchRequest): ReleaseProof {
            if (needsSelectionPublication) {
                if (!store.publishSelection()) {
                    return ReleaseProof.Unproven(OwnerFailureStage.SELECTION_PERSISTENCE, "尚未向模块宿主保存普通控制")
                }
                needsSelectionPublication = false
            }
            return client.release(request)
        }

        fun clearModuleRelease() {
            record = record.copy(moduleRelease = null)
            store.save(record)
        }

        fun resumeModuleRelease() {
            val request = SwitchRequest(newId(), ControlWay.MODULE, record.selected, record.moduleRelease?.generation)
            blocked = OwnerReadiness.SwitchBlocked(request, OwnerFailureStage.RELEASE, "尚未确认模块已释放控制连接")
            releaseRetry = scope.launch { retryModuleRelease() }
        }

        private fun adoptObservedOwner() {
            val observed = client.lastObservedGeneration ?: return
            val pending = record.moduleRelease ?: return
            if (pending.generation == observed) return
            record = record.copy(moduleRelease = pending.copy(generation = observed))
            store.save(record)
        }

        suspend fun retryModuleRelease() {
            while (scope.isActive && record.awaitingModuleRelease) {
                adoptObservedOwner()
                val request = SwitchRequest(newId(), ControlWay.MODULE, record.selected, record.moduleRelease?.generation)
                when (val proof = releaseModule(request)) {
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
                                sessions.startOrdinary()
                            }
                        }
                        refresh()
                        return
                    }
                }
            }
        }
    }

    private inner class OwnerProjection {
        private fun ownerState(view: ModuleView): OwnerState {
            val selected = record.selected
            val localHolder = ordinary?.let {
                OwnerHolder(ControlWay.ORDINARY, it.generation, local.packageName, local.processName, local.uid, local.pid)
            }
            val moduleHolder = (view as? ModuleView.Active)?.frame?.holder
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

                    ControlWay.MODULE -> view.readiness()
                }
            }
            return OwnerState(selected, readiness, if (selected == ControlWay.MODULE) moduleHolder else localHolder, lastHandoff)
        }

        fun composeControl(): ControlState {
            val view = client.view.value
            val local = servingOrdinary
            return when {
                local != null -> local.runtime.state.value.copy(platform = platform, settings = settings)

                record.selected == ControlWay.MODULE && view is ModuleView.Active -> {
                    val frame = view.frame
                    frame.state.copy(
                        platform = platform.copy(
                            permission = frame.platform.permission,
                            transport = frame.platform.transport,
                            scanPermission = frame.platform.scanPermission,
                        ),
                        settings = settings,
                    )
                }

                else -> ControlState(platform = platform, settings = settings)
            }
        }

        fun composeIntegration(): IntegrationState {
            val view = client.view.value
            return IntegrationState(
                settings = integrationSettings,
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

        private fun ModuleView.readiness(): OwnerReadiness = when (this) {
            is ModuleView.Active -> OwnerReadiness.Ready
            ModuleView.Stopped, ModuleView.Connecting -> OwnerReadiness.Starting
            is ModuleView.Unavailable -> OwnerReadiness.Unavailable(stage, reason)
            is ModuleView.Inactive -> OwnerReadiness.Unavailable(OwnerFailureStage.ACTIVATION, status.reason)
            ModuleView.Unresponsive -> OwnerReadiness.Unavailable(OwnerFailureStage.CONNECTION, UNRESPONSIVE_REASON)
        }
    }

    private fun noOwner() = OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, "当前没有就绪的控制所有者"))

    private suspend fun module(command: HostCommand): OperationResult = when (val result = client.execute(command)) {
        is HostCallResult.Operation -> result.result
        is HostCallResult.Flag -> OperationResult.Failure(ControlError(ControlErrorCode.FAILED, "控制宿主返回了不匹配的结果"))
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

    val control: ControlRuntime = object : ControlRuntime {
        override val state get() = this@ApplicationOwners.state
        override suspend fun refreshBondedDevices() {
            route({
                it.refreshBondedDevices()
                OperationResult.Success
            }, HostCommand.RefreshBondedDevices)
        }

        override suspend fun startScan() = route({ it.startScan() }, HostCommand.StartScan)

        override suspend fun stopScan() {
            route({
                it.stopScan()
                OperationResult.Success
            }, HostCommand.StopScan)
        }

        override suspend fun selectCurrentHeadset(deviceId: DeviceId) {
            route({
                it.selectCurrentHeadset(deviceId)
                OperationResult.Success
            }, HostCommand.SelectCurrentHeadset(deviceId))
        }

        override suspend fun connect(deviceId: DeviceId, pinned: Boolean) = route(
            { it.connect(deviceId, pinned) },
            if (pinned) HostCommand.ConnectFromSystem(deviceId) else HostCommand.Connect(deviceId),
        )

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

        override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult {
            if (expectedSessionId == null) {
                return route({ it.set(command) }, HostCommand.Set(null, command))
            }
            val reference = state.value.readySession?.takeIf { it.sessionId == expectedSessionId }
                ?: return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
            return system.set(reference, command)
        }

        override fun updateSettings(settings: UserSettings) {
            this@ApplicationOwners.settings = settings
            ordinary?.runtime?.updateSettings(settings)
            refresh()
        }

        override fun updatePlatformState(platformState: PlatformState) {
            platform = platformState
            ordinary?.runtime?.updatePlatformState(platformState)
            refresh()
        }
    }

    val system: SystemEntry = object : SystemEntry {
        override suspend fun connectFromSystem(deviceId: DeviceId): OperationResult {
            val initial = integration.value.owner
            if (initial.selected != ControlWay.MODULE) return control.connect(deviceId, pinned = true)
            val owner = if (initial.readiness == OwnerReadiness.Starting) {
                withTimeoutOrNull(timing.acquireTimeoutMillis) {
                    integration.first { it.owner.selected != ControlWay.MODULE || it.owner.readiness != OwnerReadiness.Starting }.owner
                }
            } else {
                initial
            }
            val readiness = owner?.readiness
            return when {
                owner == null -> OperationResult.Failure(ControlError(ControlErrorCode.TIMEOUT, "等待模块控制宿主就绪超时"))

                owner.selected != ControlWay.MODULE -> noOwner()

                readiness is OwnerReadiness.Unavailable ->
                    OperationResult.Failure(ControlError(ControlErrorCode.NOT_READY, readiness.reason))

                else -> control.connect(deviceId, pinned = true)
            }
        }

        override suspend fun set(reference: SessionReference, command: HeadsetCommand) = route(
            { it.set(reference, command) },
            HostCommand.Set(reference, command),
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
    }
}

private fun OwnerRecord.forSwitch(request: SwitchRequest): OwnerRecord = OwnerRecord(
    selected = request.to,
    moduleRelease = when {
        moduleRelease != null -> moduleRelease
        request.to == ControlWay.MODULE -> PendingModuleRelease(request.id, null)
        selected == ControlWay.MODULE -> PendingModuleRelease(request.id, request.fromGeneration)
        else -> null
    },
)

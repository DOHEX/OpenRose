package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SessionSnapshotResult {
    data class Available(
        val device: DeviceState,
        val presentation: SystemPresentationState = SystemPresentationState(),
    ) : SessionSnapshotResult
    data class Failure(val error: ControlError) : SessionSnapshotResult
}

data class PresentationTiming(val promptDurationMillis: Long = 5_000) {
    init {
        require(promptDurationMillis > 0)
    }
}

interface SystemEntry {
    suspend fun connectFromSystem(deviceId: DeviceId): OperationResult

    fun readSession(reference: SessionReference): SessionSnapshotResult

    suspend fun set(reference: SessionReference, command: HeadsetCommand): OperationResult

    suspend fun claimPrompt(reference: SessionReference, promptId: String): Boolean
}

@Suppress("TooManyFunctions")
class SystemEntryRuntime(
    private val control: ControlRuntime,
    private val scope: CoroutineScope,
    private val timing: PresentationTiming = PresentationTiming(),
    initialIntegrationSettings: IntegrationSettings = IntegrationSettings(),
) : ControlRuntime by control,
    SystemEntry {
    private data class Prompts(
        val consumed: SessionReference? = null,
        val prompt: IslandPrompt? = null,
        val inCaseSession: SessionReference? = null,
        val inCase: Pair<Boolean?, Boolean?>? = null,
        val inCaseEvents: Int = 0,
    )

    private val prompts = MutableStateFlow(Prompts())
    private val integrationSettings = MutableStateFlow(initialIntegrationSettings)
    val settings: IntegrationSettings get() = integrationSettings.value

    val presentation: StateFlow<SystemPresentationState> = PresentationFlow()

    internal fun <T> presented(source: Flow<T>, state: (T) -> ControlState): Flow<Pair<T, SystemPresentationState>> = combine(source, prompts, integrationSettings) { value, _, settings ->
        value to present(state(value), settings)
    }.distinctUntilChanged()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { control.state.collect { advance() } }
    }

    internal fun view(generation: OwnerGeneration): OwnerView {
        val captured = control.state.value
        val settings = integrationSettings.value
        return OwnerView(generation, captured, present(captured, settings), settings)
    }

    internal fun views(generation: OwnerGeneration): Flow<OwnerView> = combine(control.state, prompts, integrationSettings) { _, _, _ ->
        view(generation)
    }.distinctUntilChanged()

    override suspend fun connectFromSystem(deviceId: DeviceId): OperationResult = control.connect(deviceId, pinned = true)

    override fun readSession(reference: SessionReference): SessionSnapshotResult {
        val captured = control.state.value
        val current = captured.readySession
            ?: return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "设备会话尚未就绪"))
        if (reference != current) return SessionSnapshotResult.Failure(STALE_REFERENCE)
        return SessionSnapshotResult.Available(requireNotNull(captured.device), present(captured))
    }

    override suspend fun set(reference: SessionReference, command: HeadsetCommand): OperationResult {
        if (control.state.value.readySession != reference) return OperationResult.Failure(STALE_REFERENCE)
        return control.set(command, expectedSessionId = reference.sessionId)
    }

    override suspend fun claimPrompt(reference: SessionReference, promptId: String): Boolean {
        advance()
        val captured = control.state.value
        if (captured.readySession != reference) return false
        var claimed = false
        prompts.update { current ->
            val prompt = current.prompt
            claimed = prompt != null && prompt.id == promptId && prompt.reference == reference && !prompt.claimed &&
                prompt.appliesTo(settings)
            if (claimed) current.copy(prompt = requireNotNull(prompt).copy(claimed = true)) else current
        }
        return claimed
    }

    fun updateIntegrationSettings(settings: IntegrationSettings) {
        integrationSettings.value = settings
        advance()
    }

    internal fun present(state: ControlState, settings: IntegrationSettings = integrationSettings.value): SystemPresentationState {
        advance()
        return state.systemPresentation(settings, prompts.value.prompt)
    }

    private fun advance() {
        var installed: IslandPrompt? = null
        prompts.update { current ->
            installed = null
            val state = control.state.value
            val session = state.readySession
            var next = current.copy(prompt = current.prompt?.takeIf { it.reference == session && it.appliesTo(settings) })
            if (session != null) {
                val device = requireNotNull(state.device)
                val trusted = device.hasTrustedEarBattery()
                if (next.consumed != session && trusted) {
                    val prompt = newPrompt(IslandTiming.CONNECTION, session, "connection")
                    installed = prompt
                    next = next.copy(consumed = session, prompt = prompt)
                }
                val flags = device.inCaseFlags()
                val previous = next.inCase.takeIf { next.inCaseSession == session }
                val entered = previous != null && flags != null &&
                    ((previous.first == false && flags.first == true) || (previous.second == false && flags.second == true))
                next = next.copy(inCaseSession = session, inCase = flags)
                if (entered && trusted) {
                    val event = next.inCaseEvents + 1
                    next = next.copy(inCaseEvents = event)
                    newPrompt(IslandTiming.IN_CASE, session, "in_case:$event")?.let { prompt ->
                        installed = prompt
                        next = next.copy(prompt = prompt)
                    }
                }
            }
            next
        }
        installed?.let(::expireLater)
    }

    private fun newPrompt(timing: IslandTiming, session: SessionReference, event: String): IslandPrompt? {
        if (!settings.allowsPrompt(timing)) return null
        return IslandPrompt(
            id = "${session.deviceId.value}:${session.sessionId}:$event",
            timing = timing,
            mode = settings.islandMode,
            reference = session,
            durationMillis = this.timing.promptDurationMillis,
        )
    }

    private fun expireLater(prompt: IslandPrompt) {
        scope.launch {
            delay(prompt.durationMillis)
            prompts.update { if (it.prompt?.id == prompt.id) it.copy(prompt = null) else it }
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private inner class PresentationFlow : StateFlow<SystemPresentationState> {
        override val value: SystemPresentationState get() = present(control.state.value)
        override val replayCache: List<SystemPresentationState> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<SystemPresentationState>): Nothing {
            combine(control.state, prompts, integrationSettings) { state, _, _ -> present(state) }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    private companion object {
        val STALE_REFERENCE = ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换")
    }
}

private fun DeviceState.hasTrustedEarBattery(): Boolean = publishesSystemBattery &&
    listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY).any { field ->
        val value = knownValue(field) as? FieldValue.Number
        value?.value in 0..100
    }

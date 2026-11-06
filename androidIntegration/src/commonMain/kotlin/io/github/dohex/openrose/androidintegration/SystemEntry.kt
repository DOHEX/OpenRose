package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
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

/** A detached read of one specific device session, with the system view derived from the same capture. */
sealed interface SessionSnapshotResult {
    data class Available(
        val device: DeviceState,
        val presentation: SystemPresentationState = SystemPresentationState(),
    ) : SessionSnapshotResult
    data class Failure(val error: ControlError) : SessionSnapshotResult
}

/** Deadlines of the system presentation; injectable so a virtual clock can drive them. */
data class PresentationTiming(val connectionPromptDurationMillis: Long = 5_000) {
    init {
        require(connectionPromptDurationMillis > 0)
    }
}

/**
 * What system entries (notifications, the fusion device center, system headset UIs) do through the control
 * entry. Every operation but [connectFromSystem] names the device session it was issued for and is refused
 * unless that session is still the current one; an entry never holds a session of its own.
 */
interface SystemEntry {
    /** Pins [deviceId] as the target device, so later audio-connection changes do not replace it. */
    suspend fun connectFromSystem(deviceId: DeviceId): OperationResult

    /** Reads a detached snapshot only when [reference] still identifies the ready device session. */
    fun readSession(reference: SessionReference): SessionSnapshotResult

    /** A setting for the session [reference] names; it succeeds only after the earphones confirm it. */
    suspend fun set(reference: SessionReference, setting: DeviceSetting, value: FieldValue): OperationResult

    /** The first current renderer to claim an active prompt becomes its sole trigger owner. */
    suspend fun claimConnectionPrompt(reference: SessionReference, promptId: String): Boolean
}

/**
 * One control entry with the system entries on top. It derives the system presentation from the control
 * state, decides when a session's connection prompt shows and for how long, and checks every reference
 * against the current session before calling the same control entry. It holds no session, queue or link.
 */
class SystemEntryRuntime(
    private val control: ControlRuntime,
    private val scope: CoroutineScope,
    private val timing: PresentationTiming = PresentationTiming(),
    initialIntegrationSettings: IntegrationSettings = IntegrationSettings(),
) : ControlRuntime by control,
    SystemEntry {
    /** [consumed] is the session whose connection event was used up; it never prompts again. */
    private data class Prompts(val consumed: SessionReference? = null, val prompt: ConnectionPrompt? = null)

    private val prompts = MutableStateFlow(Prompts())
    private val integrationSettings = MutableStateFlow(initialIntegrationSettings)
    val settings: IntegrationSettings get() = integrationSettings.value

    /** The system view of the current ready session; reading it is always current with [state]. */
    val presentation: StateFlow<SystemPresentationState> = PresentationFlow()

    init {
        // Each session's connection event is consumed when it happens, whether or not anyone is reading.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { control.state.collect { advance() } }
    }

    /** Control and system presentation from one capture, so a replacement never mixes two sessions. */
    internal fun view(generation: OwnerGeneration): OwnerView {
        val captured = control.state.value
        val settings = integrationSettings.value
        return OwnerView(generation, captured, present(captured, settings), settings)
    }

    /** Prompt claims and expiry wake readers without combining independently derived session views. */
    internal fun views(generation: OwnerGeneration): Flow<OwnerView> = combine(control.state, prompts, integrationSettings) { _, _, _ -> view(generation) }.distinctUntilChanged()

    override suspend fun connectFromSystem(deviceId: DeviceId): OperationResult = control.connect(deviceId, pinned = true)

    override fun readSession(reference: SessionReference): SessionSnapshotResult {
        val captured = control.state.value
        if (captured.connection !is ConnectionState.Connected) {
            return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "设备会话尚未就绪"))
        }
        val current = captured.readySession
            ?: return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "当前设备状态与连接会话不一致"))
        if (reference != current) return SessionSnapshotResult.Failure(STALE_REFERENCE)
        return SessionSnapshotResult.Available(requireNotNull(captured.device), present(captured).detached())
    }

    override suspend fun set(reference: SessionReference, setting: DeviceSetting, value: FieldValue): OperationResult {
        if (control.state.value.readySession != reference) return OperationResult.Failure(STALE_REFERENCE)
        return control.set(setting, value, expectedSessionId = reference.sessionId)
    }

    override suspend fun claimConnectionPrompt(reference: SessionReference, promptId: String): Boolean {
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

    private fun present(state: ControlState, settings: IntegrationSettings = integrationSettings.value): SystemPresentationState {
        advance()
        return state.systemPresentation(settings, prompts.value.prompt)
    }

    /**
     * Brings the prompt in line with the current control state. A ready session's connection event is
     * consumed once, at its first trusted ear battery, and shows only if the settings allow it at that
     * moment; a prompt whose session ended or whose mode no longer applies is dropped for good.
     */
    private fun advance() {
        var installed: ConnectionPrompt? = null
        prompts.update { current ->
            installed = null
            // Read inside the update, so a retry never acts on a state older than the one it replaces.
            val state = control.state.value
            val session = state.readySession
            val kept = current.prompt?.takeIf { it.reference == session && it.appliesTo(settings) }
            if (session == null || current.consumed == session || !requireNotNull(state.device).hasTrustedEarBattery()) {
                if (kept == current.prompt) current else current.copy(prompt = kept)
            } else {
                val prompt = if (settings.allowsConnectionPrompt()) {
                    ConnectionPrompt(
                        id = "${session.deviceId.value}:${session.sessionId}:connection",
                        mode = settings.islandMode,
                        reference = session,
                        durationMillis = timing.connectionPromptDurationMillis,
                    )
                } else {
                    null
                }
                installed = prompt
                Prompts(consumed = session, prompt = prompt)
            }
        }
        installed?.let(::expireLater)
    }

    /** A timer clears only its own prompt, so an old session's timer can never clear the next one's. */
    private fun expireLater(prompt: ConnectionPrompt) {
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

private fun DeviceState.hasTrustedEarBattery(): Boolean = listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY).any { field ->
    val value = (fields[field] as? FieldReading.Known)?.value as? FieldValue.Number
    value?.value in 0..100
}

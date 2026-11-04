package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.NoiseLevelCapabilities
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
) : ControlRuntime by control, SystemEntry {
    /** [consumed] is the session whose connection event was used up; it never prompts again. */
    private data class Prompts(val consumed: SessionReference? = null, val prompt: ConnectionPrompt? = null)

    private val prompts = MutableStateFlow(Prompts())

    /** The system view of the current ready session; reading it is always current with [state]. */
    val presentation: StateFlow<SystemPresentationState> = PresentationFlow()

    init {
        // Each session's connection event is consumed when it happens, whether or not anyone is reading.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { control.state.collect { advance() } }
    }

    override suspend fun connectFromSystem(deviceId: DeviceId): OperationResult = control.connect(deviceId, pinned = true)

    override fun readSession(reference: SessionReference): SessionSnapshotResult {
        val captured = control.state.value
        if (captured.connection !is ConnectionState.Connected) {
            return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "设备会话尚未就绪"))
        }
        val current = captured.readySession
            ?: return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "当前设备状态与连接会话不一致"))
        if (reference != current) return SessionSnapshotResult.Failure(STALE_REFERENCE)
        return SessionSnapshotResult.Available(requireNotNull(captured.device).detached(), present(captured).detached())
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
                prompt.appliesTo(captured.settings)
            if (claimed) current.copy(prompt = requireNotNull(prompt).copy(claimed = true)) else current
        }
        return claimed
    }

    override fun updateSettings(settings: UserSettings) {
        control.updateSettings(settings)
        // A prompt of a mode that was left, or that the settings no longer allow, ends now and is never replayed.
        advance()
    }

    private fun present(state: ControlState): SystemPresentationState {
        advance()
        return state.systemPresentation(prompts.value.prompt)
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
            val kept = current.prompt?.takeIf { it.reference == session && it.appliesTo(state.settings) }
            if (session == null || current.consumed == session || !requireNotNull(state.device).hasTrustedEarBattery()) {
                if (kept == current.prompt) current else current.copy(prompt = kept)
            } else {
                val prompt = if (state.settings.allowsConnectionPrompt()) {
                    ConnectionPrompt(
                        id = "${session.deviceId.value}:${session.sessionId}:connection",
                        mode = state.settings.islandMode,
                        reference = session,
                        durationMillis = timing.connectionPromptDurationMillis,
                    )
                } else null
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
            combine(control.state, prompts) { state, _ -> present(state) }.distinctUntilChanged().collect(collector)
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

/** A copy that shares no collection with the session's own state, so a consumer cannot change it. */
private fun DeviceState.detached() = copy(
    capabilities = capabilities.detached(),
    fields = fields.mapValues { (_, reading) -> reading.detached() },
    fieldErrors = fieldErrors.toMap(),
    rawNotifications = rawNotifications.toList(),
    unknownNotifications = unknownNotifications.toList(),
    warnings = warnings.toList(),
)

private fun FieldReading.detached(): FieldReading = when (this) {
    FieldReading.Loading -> this
    is FieldReading.NotProvided -> copy()
    is FieldReading.Unknown -> copy()
    is FieldReading.Known -> copy(value = value.detached())
}

private fun FieldValue.detached(): FieldValue = when (this) {
    is FieldValue.NoiseCycle -> copy(flags = flags.toMap())
    is FieldValue.IntegerList -> copy(values = values.toList())
    is FieldValue.Firmware -> copy(parts = parts.toList())
    is FieldValue.TouchKeys -> copy(bindings = bindings.toList())
    else -> this
}

private fun DeviceCapabilities.detached() = copy(
    readableFields = readableFields.toSet(),
    writableSettings = writableSettings.toSet(),
    noiseControl = noiseControl?.let { noise ->
        noise.copy(
            modes = noise.modes.toSet(),
            cycleModes = noise.cycleModes.toSet(),
            ancLevel = noise.ancLevel?.detached(),
            transparencyLevel = noise.transparencyLevel?.detached(),
        )
    },
    equalizer = equalizer?.let { eq ->
        eq.copy(presets = eq.presets.toSet(), custom = eq.custom?.let { it.copy(bandsHz = it.bandsHz.toList()) })
    },
    touchControl = touchControl?.let { it.copy(slots = it.slots.toSet(), actions = it.actions.toSet()) },
    audioControl = audioControl?.let { it.copy(modes = it.modes.toSet()) },
    promptVolumeLevels = promptVolumeLevels.toSet(),
    gainLevels = gainLevels.toSet(),
    hearingProtectionLevels = hearingProtectionLevels.toSet(),
)

private fun NoiseLevelCapabilities.detached() = copy(levels = levels.toSet(), visibleModes = visibleModes.toSet())

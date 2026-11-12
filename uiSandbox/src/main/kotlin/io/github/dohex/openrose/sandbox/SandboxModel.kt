package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.UiSettings
import io.github.dohex.openrose.ui.model.OpenRoseModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CONNECTION_STEP_MILLIS = 700L

/**
 * The shared UI's presentation model over the state a [SandboxScenario] describes. The app's own requests move the
 * scenario as a runtime would move the session: connecting steps through the connection phases on a timer, and
 * disconnecting leaves the device bonded.
 */
internal class SandboxModel(private val scope: CoroutineScope) : OpenRoseModel {
    private val mutableScenario = MutableStateFlow(SandboxScenario())
    val scenario: StateFlow<SandboxScenario> = mutableScenario.asStateFlow()

    private var lastSessionId = 1L
    private val mutableState = MutableStateFlow(
        ControlState(platform = PlatformState(PermissionState.NOT_REQUIRED))
            .showing(mutableScenario.value, lastSessionId, keepReadings = false),
    )
    override val state: StateFlow<ControlState> = mutableState.asStateFlow()

    private val mutableUiSettings = MutableStateFlow(UiSettings())
    override val uiSettings: StateFlow<UiSettings> = mutableUiSettings.asStateFlow()

    private val mutableHomeNavigationRequest = MutableStateFlow(0L)
    override val homeNavigationRequest: StateFlow<Long> = mutableHomeNavigationRequest.asStateFlow()

    private val writes = SandboxWrites(mutableState, scenario, scope)
    private var connection: Job? = null

    fun updateScenario(change: (SandboxScenario) -> SandboxScenario) {
        val previous = mutableScenario.value
        val next = change(previous).normalized()
        if (next == previous) return
        val sameSession = sameSession(previous, next)
        if (next.link >= SandboxLink.CONNECTING && !sameSession) lastSessionId++
        mutableScenario.value = next
        mutableState.update { it.showing(next, lastSessionId, keepReadings = sameSession && previous.link == SandboxLink.READY) }
    }

    override fun updateUiSettings(settings: UiSettings) {
        mutableUiSettings.value = settings
    }

    override fun updateSettings(settings: UserSettings) = mutableState.update { it.copy(settings = settings) }

    override fun startScan() = mutableState.update {
        it.copy(scan = ScanState.Scanning, candidates = sandboxScanResults.toPersistentList())
    }

    override fun stopScan() = mutableState.update { it.copy(scan = ScanState.Idle) }

    override fun connect(deviceId: DeviceId) {
        val profile = mutableState.value.profileOf(deviceId) ?: return
        connection?.cancel()
        connection = scope.launch { connectThrough(profile) }
    }

    override fun disconnect() {
        connection?.cancel()
        updateScenario { if (it.link > SandboxLink.PAIRED) it.copy(link = SandboxLink.PAIRED) else it }
    }

    /** Cancelling the attempt leaves the earlier device bonded and disconnected, as an abandoned connection would. */
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult {
        val profile = mutableState.value.profileOf(deviceId)
            ?: return OperationResult.Failure(ControlError(ControlErrorCode.UNSUPPORTED, "无法识别该设备的型号"))
        connection?.cancel()
        val before = scenario.value
        try {
            connectThrough(profile)
        } catch (cancelled: CancellationException) {
            val link = if (before.link == SandboxLink.FIRST_VISIT) SandboxLink.FIRST_VISIT else SandboxLink.PAIRED
            updateScenario { it.copy(profile = before.profile, link = link) }
            throw cancelled
        }
        return if (scenario.value.link == SandboxLink.READY) {
            OperationResult.Success
        } else {
            OperationResult.Failure(ControlError(ControlErrorCode.TRANSPORT_UNAVAILABLE, "当前平台无法建立控制连接"))
        }
    }

    override fun set(setting: DeviceSetting, value: FieldValue) = writes.submit(setting, value)

    override suspend fun setInSession(sessionId: Long, setting: DeviceSetting, value: FieldValue) = writes.write(sessionId, setting, value)

    override fun showHeadphones() = mutableHomeNavigationRequest.update { it + 1L }
}

private suspend fun SandboxModel.connectThrough(profile: ModelProfile) {
    for (link in listOf(SandboxLink.CONNECTING, SandboxLink.INITIALIZING, SandboxLink.READY)) {
        updateScenario { it.copy(profile = profile, link = link) }
        if (link != SandboxLink.READY) delay(CONNECTION_STEP_MILLIS)
    }
}

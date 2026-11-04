package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

internal class ConnectionTargeting(
    private val state: MutableStateFlow<ControlState>,
    private val transport: ControlTransport?,
    private val scope: CoroutineScope,
    private val discovery: DeviceDiscovery,
    private val link: ControlLink,
) {
    private var audioDrivenSessionDeviceId: DeviceId? = null
    private var autoConnectJob: Job? = null
    /** The newest audio-connection fact, kept while automatic control must wait for first unlock. */
    private var latestAudioDevice: DeviceId? = null
    private var systemPinnedDeviceId: DeviceId? = null
    private data class TargetIntent(val epoch: Long, val deviceId: DeviceId?)
    private val targetIntent = MutableStateFlow(TargetIntent(0L, null))

    init {
        if (transport != null) {
            scope.launch {
                discovery.refreshBondedDevices()
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.audioConnectedDevice.collect { audioDeviceId ->
                    latestAudioDevice = audioDeviceId
                    discovery.refreshBondedDevices()
                    if (state.value.platform.userUnlocked) handleAudioConnectedDevice(audioDeviceId)
                }
            }
        }
    }

    private suspend fun handleAudioConnectedDevice(audioDeviceId: DeviceId?) {
        if (audioDeviceId == null) {
            if (audioDrivenSessionDeviceId != null) {
                disconnect()
            }
            return
        }
        if (systemPinnedDeviceId != null) {
            return
        }
        val requestEpoch = targetIntent.updateAndGet { current ->
            if (current.deviceId == audioDeviceId && current.epoch > 0) current
            else TargetIntent(current.epoch + 1, audioDeviceId)
        }.epoch
        val candidate = discovery.findCandidate(audioDeviceId)
        if (isRequestSuperseded(requestEpoch, isAudioDriven = true)) {
            return
        }
        if (candidate?.profile == null) {
            // Connected audio device is not an authorized ROSE model; do not drop existing session
            return
        }
        if (audioDrivenSessionDeviceId != null && audioDrivenSessionDeviceId != audioDeviceId) {
            disconnect()
        }
        if (state.value.connection is ConnectionState.Disconnected && systemPinnedDeviceId == null) {
            val autoEpoch = targetIntent.updateAndGet { current ->
                if (current.deviceId == audioDeviceId && current.epoch > 0) current
                else TargetIntent(current.epoch + 1, audioDeviceId)
            }.epoch
            audioDrivenSessionDeviceId = audioDeviceId
            autoConnectJob?.cancel()
            autoConnectJob = scope.launch {
                connectInternal(audioDeviceId, isAudioDriven = true, requestEpoch = autoEpoch)
            }
        }
    }

    suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult {
        val requestEpoch = targetIntent.updateAndGet { current ->
            if (current.deviceId == deviceId && current.epoch > 0) current
            else TargetIntent(current.epoch + 1, deviceId)
        }.epoch
        if (pinned || systemPinnedDeviceId != null) {
            systemPinnedDeviceId = deviceId
        }
        val target = discovery.resolveTarget(deviceId)
        state.update { it.copy(connectionTarget = target) }
        return connectInternal(deviceId, isAudioDriven = false, requestEpoch = requestEpoch)
    }

    /** Cancelling the adding flow releases only the unfinished attempt it created. */
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult {
        val requestEpoch = targetIntent.updateAndGet { current ->
            if (current.deviceId == deviceId && current.epoch > 0) current
            else TargetIntent(current.epoch + 1, deviceId)
        }.epoch
        if (systemPinnedDeviceId != null) {
            systemPinnedDeviceId = deviceId
        }
        val target = discovery.resolveTarget(deviceId)
        state.update { it.copy(connectionTarget = target) }
        return connectInternal(deviceId, isAudioDriven = false, cancelWithCaller = true, requestEpoch = requestEpoch)
    }

    private fun isRequestSuperseded(requestEpoch: Long, isAudioDriven: Boolean, deviceId: DeviceId? = null): Boolean {
        val current = targetIntent.value
        if (requestEpoch != current.epoch) return true
        if (isAudioDriven && systemPinnedDeviceId != null) return true
        if (deviceId != null && systemPinnedDeviceId != null && systemPinnedDeviceId != deviceId) return true
        return false
    }

    private suspend fun connectInternal(
        deviceId: DeviceId,
        isAudioDriven: Boolean,
        cancelWithCaller: Boolean = false,
        requestEpoch: Long,
    ): OperationResult {
        if (isRequestSuperseded(requestEpoch, isAudioDriven)) {
            return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接请求已被替换"))
        }
        platformError(state.value, transport)?.let { return failure(it) }
        val candidate = discovery.findCandidate(deviceId)
        if (isRequestSuperseded(requestEpoch, isAudioDriven)) {
            return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接请求已被替换"))
        }
        if (candidate != null) {
            if (state.value.connectionTarget?.deviceId == deviceId || (isAudioDriven && systemPinnedDeviceId == null)) {
                if (state.value.connectionTarget != candidate) {
                    state.update { it.copy(connectionTarget = candidate) }
                }
            }
        }
        if (candidate?.profile == null) return failure(ControlError(ControlErrorCode.UNSUPPORTED, "没有受支持的型号识别证据"))
        discovery.stopScan()
        if (isRequestSuperseded(requestEpoch, isAudioDriven)) {
            return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接请求已被替换"))
        }
        if (!isAudioDriven) {
            autoConnectJob?.cancel()
            autoConnectJob = null
        }
        return link.connect(
            candidate = candidate,
            cancelWithCaller = cancelWithCaller,
            isSuperseded = { isRequestSuperseded(requestEpoch, isAudioDriven, deviceId) },
            onStarted = { audioDrivenSessionDeviceId = if (isAudioDriven) deviceId else null },
            onAttemptFailed = {
                if (audioDrivenSessionDeviceId == deviceId) audioDrivenSessionDeviceId = null
            },
        )
    }

    suspend fun disconnect(): Boolean {
        targetIntent.updateAndGet { current ->
            TargetIntent(current.epoch + 1, null)
        }
        audioDrivenSessionDeviceId = null
        autoConnectJob?.cancel()
        autoConnectJob = null
        return link.disconnect()
    }

    fun updatePlatformState(platformState: PlatformState) {
        val unlocking = !state.value.platform.userUnlocked && platformState.userUnlocked
        state.update { it.copy(platform = platformState) }
        // The audio connection may predate this owner or the unlock; only the current one is resumed.
        val audioDevice = latestAudioDevice
        if (unlocking && audioDevice != null && transport != null) {
            scope.launch { handleAudioConnectedDevice(audioDevice) }
        }
    }

    private fun failure(error: ControlError): OperationResult.Failure {
        state.update { it.copy(lastError = error) }
        return OperationResult.Failure(error)
    }
}

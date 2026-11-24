package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    private var systemPinnedDeviceId: DeviceId? = null
    private data class TargetIntent(val epoch: Long, val deviceId: DeviceId?)
    private val targetIntent = MutableStateFlow(TargetIntent(0L, null))

    private val audioPermission = MutableStateFlow(state.value.platform.canReadAudio)

    init {
        if (transport != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioPermission.collectLatest { permitted ->
                    state.update { it.copy(audioDeviceId = null) }
                    if (permitted) {
                        coroutineScope {
                            launch { discovery.refreshBondedDevices() }
                            launch { transport.bondedDevicesChanged.collect { discovery.refreshBondedDevices() } }
                            transport.audioConnectedDevice.collect { audioDeviceId ->
                                state.update { it.copy(audioDeviceId = audioDeviceId) }
                                discovery.refreshBondedDevices()
                                if (state.value.platform.userUnlocked) handleAudioConnectedDevice(audioDeviceId)
                            }
                        }
                    }
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
        val current = state.value
        if (current.connection !is ConnectionState.Disconnected && current.deviceHasSystemAudio) {
            audioDrivenSessionDeviceId = current.device?.deviceId
            return
        }
        if (systemPinnedDeviceId != null) {
            return
        }
        val requestEpoch = targetEpoch(audioDeviceId)
        val candidate = discovery.findCandidate(audioDeviceId)
        if (isRequestSuperseded(requestEpoch, isAudioDriven = true)) {
            return
        }
        if (candidate?.profile == null) {
            return
        }
        if (audioDrivenSessionDeviceId != null && audioDrivenSessionDeviceId != audioDeviceId) {
            disconnect()
        }
        if (state.value.connection is ConnectionState.Disconnected && systemPinnedDeviceId == null) {
            val autoEpoch = targetEpoch(audioDeviceId)
            audioDrivenSessionDeviceId = audioDeviceId
            autoConnectJob?.cancel()
            autoConnectJob = scope.launch {
                connectInternal(audioDeviceId, isAudioDriven = true, requestEpoch = autoEpoch)
            }
        }
    }

    suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult {
        val requestEpoch = targetEpoch(deviceId)
        if (pinned || systemPinnedDeviceId != null) {
            systemPinnedDeviceId = deviceId
        }
        val target = discovery.resolveTarget(deviceId)
        state.update { it.copy(connectionTarget = target) }
        return connectInternal(deviceId, isAudioDriven = false, requestEpoch = requestEpoch)
    }

    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult {
        val requestEpoch = targetEpoch(deviceId)
        if (systemPinnedDeviceId != null) {
            systemPinnedDeviceId = deviceId
        }
        val target = discovery.resolveTarget(deviceId)
        state.update { it.copy(connectionTarget = target) }
        return connectInternal(deviceId, isAudioDriven = false, cancelWithCaller = true, requestEpoch = requestEpoch)
    }

    private fun targetEpoch(deviceId: DeviceId): Long = targetIntent.updateAndGet { current ->
        if (current.deviceId == deviceId) current else TargetIntent(current.epoch + 1, deviceId)
    }.epoch

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
            return supersededConnection()
        }
        val unavailable = platformError(state.value, transport)
        if (unavailable != null) return failure(unavailable)
        val candidate = discovery.findCandidate(deviceId)
        return if (isRequestSuperseded(requestEpoch, isAudioDriven)) {
            supersededConnection()
        } else {
            val adoptTarget = state.value.connectionTarget?.deviceId == deviceId || (isAudioDriven && systemPinnedDeviceId == null)
            if (candidate != null && adoptTarget) {
                state.update { it.copy(connectionTarget = candidate) }
            }
            val profile = candidate?.profile
            if (profile == null) {
                failure(ControlError(ControlErrorCode.UNSUPPORTED, "没有受支持的型号识别证据"))
            } else {
                connectCandidate(candidate, profile, isAudioDriven, cancelWithCaller, requestEpoch)
            }
        }
    }

    private suspend fun connectCandidate(
        candidate: DeviceCandidate,
        profile: ModelProfile,
        isAudioDriven: Boolean,
        cancelWithCaller: Boolean,
        requestEpoch: Long,
    ): OperationResult {
        discovery.stopScan()
        if (isRequestSuperseded(requestEpoch, isAudioDriven)) {
            return supersededConnection()
        }
        if (!isAudioDriven) {
            autoConnectJob?.cancel()
            autoConnectJob = null
        }
        return link.connect(
            candidate = candidate,
            profile = profile,
            cancelWithCaller = cancelWithCaller,
            isSuperseded = { isRequestSuperseded(requestEpoch, isAudioDriven, candidate.deviceId) },
            onStarted = {
                audioDrivenSessionDeviceId = candidate.deviceId.takeIf {
                    isAudioDriven || candidate.matchesAudioDevice(state.value.audioDeviceId)
                }
            },
            onAttemptFailed = {
                if (audioDrivenSessionDeviceId == candidate.deviceId) audioDrivenSessionDeviceId = null
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
        val previous = state.value.platform
        val unlocking = !previous.userUnlocked && platformState.userUnlocked
        val transportReady = previous.transport != TransportAvailability.Available &&
            platformState.transport == TransportAvailability.Available
        state.update { it.copy(platform = platformState) }
        audioPermission.value = platformState.canReadAudio
        if (!previous.canReadAudio || !platformState.canReadAudio || !platformState.userUnlocked) return
        if ((unlocking || transportReady) && transport != null) {
            scope.launch { state.value.audioDeviceId?.let { handleAudioConnectedDevice(it) } }
        }
    }

    private fun failure(error: ControlError): OperationResult.Failure {
        state.update { it.copy(lastError = error) }
        return OperationResult.Failure(error)
    }
}

private val PlatformState.canReadAudio: Boolean get() = !permission.missing

private fun supersededConnection() = OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接请求已被替换"))

package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelIdentification
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeviceDiscovery(
    private val state: MutableStateFlow<ControlState>,
    private val transport: ControlTransport?,
    private val scope: CoroutineScope,
) {
    private val scanLifecycle = Mutex()
    private val scanEpoch = MutableStateFlow(0L)
    private var scanJob: Job? = null

    suspend fun refreshBondedDevices() {
        val bonded = runCatching { transport?.bondedDevices() }.getOrNull() ?: emptyList()
        state.update { it.copy(bondedDevices = bonded.toPersistentList()) }
    }

    suspend fun findCandidate(deviceId: DeviceId): DeviceCandidate? {
        state.value.candidates.firstOrNull { it.deviceId == deviceId }?.let { return it }
        state.value.bondedDevices.firstOrNull { it.deviceId == deviceId }?.let { return it }
        val bonded = runCatching { transport?.bondedDevices()?.firstOrNull { it.deviceId == deviceId } }.getOrNull()
        if (bonded != null) {
            state.update { old ->
                if (old.bondedDevices.none { it.deviceId == bonded.deviceId }) {
                    old.copy(bondedDevices = old.bondedDevices.adding(bonded))
                } else {
                    old
                }
            }
            return bonded
        }
        state.value.device?.takeIf { it.deviceId == deviceId }?.let {
            return DeviceCandidate(
                deviceId = it.deviceId,
                name = null,
                rssi = null,
                profile = it.profile,
                manufacturerDataHex = "",
                classicAddress = it.classicAddress,
            )
        }
        state.value.connectionTarget?.takeIf { it.deviceId == deviceId && it.profile != null }?.let {
            return it
        }
        return null
    }

    fun resolveTarget(deviceId: DeviceId): DeviceCandidate {
        val current = state.value
        current.candidates.firstOrNull { it.deviceId == deviceId }?.let { return it }
        current.bondedDevices.firstOrNull { it.deviceId == deviceId }?.let { return it }
        current.connectionTarget?.takeIf { it.deviceId == deviceId }?.let { return it }
        current.device?.takeIf { it.deviceId == deviceId }?.let {
            return DeviceCandidate(
                deviceId = it.deviceId,
                name = null,
                rssi = null,
                profile = it.profile,
                manufacturerDataHex = "",
                classicAddress = it.classicAddress,
            )
        }
        return DeviceCandidate(
            deviceId = deviceId,
            name = null,
            rssi = null,
            profile = null,
            manufacturerDataHex = "",
        )
    }

    suspend fun startScan(): OperationResult = scanLifecycle.withLock {
        platformError(state.value, transport)?.let { error ->
            state.update { it.copy(lastError = error) }
            return@withLock OperationResult.Failure(error)
        }
        if (scanJob?.isActive == true) return@withLock OperationResult.Success
        val epoch = scanEpoch.value + 1
        scanEpoch.value = epoch
        state.update { it.copy(scan = ScanState.Scanning, candidates = persistentListOf(), lastError = null) }
        scanJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                requireNotNull(transport).scan().collect { advertisement ->
                    val manufacturerDataHex = ModelIdentification.normalizedHex(advertisement.manufacturerData)
                    val profile = ModelIdentification.identify(advertisement.manufacturerData)
                    val candidate = DeviceCandidate(
                        advertisement.deviceId,
                        advertisement.name,
                        advertisement.rssi,
                        profile,
                        manufacturerDataHex,
                        ModelIdentification.classicAddress(manufacturerDataHex, profile),
                    )
                    state.update { old ->
                        if (scanEpoch.value != epoch) {
                            old
                        } else {
                            val index = old.candidates.indexOfFirst { it.deviceId == candidate.deviceId }
                            val candidates = if (index < 0) {
                                old.candidates.adding(candidate)
                            } else {
                                old.candidates.replacingAt(index, candidate)
                            }
                            old.copy(candidates = candidates)
                        }
                    }
                }
                state.update { if (scanEpoch.value == epoch) it.copy(scan = ScanState.Idle) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                val error = ControlError(ControlErrorCode.FAILED, cause.message ?: "扫描失败")
                state.update { if (scanEpoch.value == epoch) it.copy(scan = ScanState.Failed(error), lastError = error) else it }
            }
        }
        val failed = state.value.scan as? ScanState.Failed
        failed?.let { OperationResult.Failure(it.error) } ?: OperationResult.Success
    }

    suspend fun stopScan() = scanLifecycle.withLock {
        scanEpoch.value++
        scanJob?.cancelAndJoin()
        scanJob = null
        state.update { it.copy(scan = ScanState.Idle) }
    }
}

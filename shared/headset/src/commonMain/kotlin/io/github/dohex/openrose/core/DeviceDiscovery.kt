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
        val bonded = try {
            transport?.bondedDevices().orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        state.update { it.copy(bondedDevices = bonded.toPersistentList()) }
    }

    suspend fun findCandidate(deviceId: DeviceId): DeviceCandidate? {
        val cached = state.value.knownCandidate(deviceId)
        if (cached?.profile != null) return cached
        refreshBondedDevices()
        return state.value.knownCandidate(deviceId)
    }

    fun resolveTarget(deviceId: DeviceId): DeviceCandidate = state.value.knownCandidate(deviceId)
        ?: DeviceCandidate(deviceId, null, null, null, "")

    private fun ControlState.knownCandidate(id: DeviceId): DeviceCandidate? {
        val scanned = candidates.firstOrNull { it.deviceId == id }
        val bonded = bondedDevices.firstOrNull { it.deviceId == id }
        val active = device?.takeIf { it.deviceId == id }?.let {
            DeviceCandidate(id, null, null, it.profile, "", it.classicAddress)
        }
        val known = listOfNotNull(bonded, active, connectionTarget?.takeIf { it.deviceId == id }).firstOrNull { it.profile != null }
        return scanned?.withEvidence(known) ?: known ?: bonded
    }

    private fun DeviceCandidate.withEvidence(previous: DeviceCandidate?): DeviceCandidate {
        if (previous == null || (profile != null && previous.profile != profile)) return this
        return copy(
            name = name ?: previous.name,
            profile = profile ?: previous.profile,
            manufacturerDataHex = if (profile == null && previous.profile != null) previous.manufacturerDataHex else manufacturerDataHex,
            classicAddress = classicAddress ?: previous.classicAddress,
        )
    }

    suspend fun startScan(): OperationResult = scanLifecycle.withLock {
        val current = state.value
        val denied = if (current.platform.scanPermission.missing) {
            ControlError(ControlErrorCode.PERMISSION_REQUIRED, "需要扫描权限才能搜索附近耳机")
        } else {
            platformError(current, transport)
        }
        denied?.let { error ->
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
                            val identified = candidate.withEvidence(old.knownCandidate(candidate.deviceId))
                            val candidates = if (index < 0) {
                                old.candidates.adding(identified)
                            } else {
                                old.candidates.replacingAt(index, identified)
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

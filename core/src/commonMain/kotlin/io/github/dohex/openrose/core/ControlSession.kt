package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelIdentification
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Deadlines are injectable so public behavior can be tested with a virtual clock. */
data class SessionTiming(
    val connectTimeoutMillis: Long = 15_000,
    val queryTimeoutMillis: Long = 1_500,
    val i5WriteIntervalMillis: Long = 100,
    val i7WriteIntervalMillis: Long = 100,
) {
    init { require(connectTimeoutMillis > 0 && queryTimeoutMillis > 0 && i5WriteIntervalMillis >= 0 && i7WriteIntervalMillis >= 0) }
}

/** Platform-owned authority. Replacing a page or its interface style never replaces this owner. */
class ControlSession(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
    private val transport: ControlTransport? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val timing: SessionTiming = SessionTiming(),
) {
    private val mutableState = MutableStateFlow(ControlState(platform = initialPlatformState, settings = initialSettings.snapshot()))
    val state: StateFlow<ControlState> = mutableState.asStateFlow()
    private val lifecycle = Mutex()
    private val scanLifecycle = Mutex()
    private val scanEpoch = MutableStateFlow(0L)
    private var scanJob: Job? = null
    private var generation = 0L
    private var active: Attempt? = null

    private class Attempt(val id: Long, val candidate: DeviceCandidate) {
        lateinit var task: Deferred<OperationResult>
        var connection: ControlConnection? = null
        var observer: Job? = null
    }

    suspend fun startScan(): OperationResult = scanLifecycle.withLock {
        platformError()?.let { return@withLock failure(it) }
        if (scanJob?.isActive == true) return@withLock OperationResult.Success
        val epoch = scanEpoch.value + 1
        scanEpoch.value = epoch
        mutableState.update { it.copy(scan = ScanState.Scanning, candidates = emptyList(), lastError = null) }
        scanJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                requireNotNull(transport).scan().collect { advertisement ->
                    val candidate = DeviceCandidate(advertisement.deviceId, advertisement.name, advertisement.rssi,
                        ModelIdentification.identify(advertisement.manufacturerData), ModelIdentification.normalizedHex(advertisement.manufacturerData))
                    mutableState.update { old -> if (scanEpoch.value == epoch) old.copy(candidates = old.candidates.filterNot { it.deviceId == candidate.deviceId } + candidate) else old }
                }
                mutableState.update { if (scanEpoch.value == epoch) it.copy(scan = ScanState.Idle) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                val error = ControlError(ControlErrorCode.FAILED, cause.message ?: "扫描失败")
                mutableState.update { if (scanEpoch.value == epoch) it.copy(scan = ScanState.Failed(error), lastError = error) else it }
            }
        }
        val failed = state.value.scan as? ScanState.Failed
        failed?.let { OperationResult.Failure(it.error) } ?: OperationResult.Success
    }

    suspend fun stopScan() = scanLifecycle.withLock {
        scanEpoch.value++
        scanJob?.cancelAndJoin()
        scanJob = null
        mutableState.update { it.copy(scan = ScanState.Idle) }
    }

    suspend fun connect(deviceId: DeviceId): OperationResult {
        platformError()?.let { return failure(it) }
        val candidate = state.value.candidates.firstOrNull { it.deviceId == deviceId }
        if (candidate?.profile == null) return failure(ControlError(ControlErrorCode.UNSUPPORTED, "没有受支持的型号识别证据"))
        stopScan()
        val attempt = lifecycle.withLock {
            active?.takeIf { it.candidate.deviceId == deviceId && state.value.connection !is ConnectionState.Disconnected }?.let { return@withLock it }
            clearActive()
            val next = Attempt(++generation, candidate)
            val profile = requireNotNull(candidate.profile)
            mutableState.update {
                it.copy(connection = ConnectionState.Connecting(deviceId, next.id, ConnectionPhase.CONNECTING),
                    device = DeviceState(deviceId, next.id, profile, DeviceCapabilities(profile.supportedFields), profile.supportedFields.associateWith { FieldReading.Loading }),
                    lastError = null)
            }
            active = next
            next.task = scope.async(start = CoroutineStart.LAZY) { openAndRead(next) }
            next
        }
        attempt.task.start()
        return try {
            attempt.task.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                attempt.task.cancel()
                throw cancelled
            }
            // Disconnect cancels the owned operation, without cancelling another caller's scope.
            OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "连接会话已结束"))
        }
    }

    suspend fun disconnect() {
        lifecycle.withLock {
            clearActive()
            mutableState.update { it.copy(connection = ConnectionState.Disconnected, device = null, lastError = null) }
        }
    }

    /** Writes are exposed only once their model-specific device confirmation behavior exists. */
    suspend fun set(setting: DeviceSetting, value: FieldValue): OperationResult {
        platformError()?.let { return failure(it) }
        if (state.value.connection !is ConnectionState.Connected) return failure(ControlError(ControlErrorCode.NOT_READY, "设备尚未就绪"))
        return failure(ControlError(ControlErrorCode.UNSUPPORTED, "${setting.name} 的写入与设备确认尚未实现"))
    }

    private suspend fun openAndRead(attempt: Attempt): OperationResult {
        val candidate = attempt.candidate
        val profile = requireNotNull(candidate.profile)
        val codec = createProtocolCodec(profile)
        var connection: ControlConnection? = null
        var ready = false
        try {
            val gatt = profile.gatt
            connection = withTimeout(timing.connectTimeoutMillis) {
                requireNotNull(transport).connect(candidate.deviceId, TransportEndpoint.BleGatt(gatt.serviceUuid, gatt.writeUuid, gatt.notifyUuid)) { phase ->
                    phase(attempt, phase)
                }
            }
            lifecycle.withLock {
                if (active !== attempt) throw CancellationException("Stale connection")
                attempt.connection = connection
            }
            val progress = MutableStateFlow(ReadProgress())
            val link = connection
            attempt.observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var error = ControlError(ControlErrorCode.DISCONNECTED, "设备已断开")
                try {
                    link.notifications.collect { bytes ->
                        val parsed = codec.feedNotification(bytes.copyOf())
                        updateAttempt(attempt) { current ->
                            val old = requireNotNull(current.device)
                            val readings = parsed.flatMap { it.readings.entries }.associate { it.toPair() }
                            current.copy(device = old.copy(fields = old.fields + readings,
                                fieldErrors = old.fieldErrors - readings.keys,
                                rawNotifications = (old.rawNotifications + bytes.hex()).takeLast(128),
                                unknownNotifications = (old.unknownNotifications + parsed.filter { !it.accepted }.map { it.rawHex }).takeLast(128),
                                warnings = (old.warnings + parsed.flatMap { it.warnings }).takeLast(128)))
                        }
                        progress.update { previous -> previous.copy(keys = previous.keys + parsed.flatMap { it.responseKeys }, meaningful = previous.meaningful || parsed.any { it.accepted }) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    error = ControlError(ControlErrorCode.DISCONNECTED, cause.message ?: "设备已断开")
                } finally {
                    progress.update { it.copy(ended = true) }
                    withContext(NonCancellable) {
                        lifecycle.withLock {
                            if (active === attempt) {
                                active = null
                                updateAttempt(attempt) { if (it.connection is ConnectionState.Disconnected) it else it.copy(connection = ConnectionState.Disconnected, device = null, lastError = error) }
                            }
                        }
                    }
                    closeSafely(link)
                }
            }
            phase(attempt, ConnectionPhase.SUBSCRIBING)
            withTimeout(timing.connectTimeoutMillis) { connection.subscribe() }
            phase(attempt, ConnectionPhase.INITIALIZING)
            val queries = codec.initQueries(connection.maxWriteSize)
            for ((index, query) in queries.withIndex()) {
                check(query.bytes.size <= connection.maxWriteSize) { "完整协议查询超出传输写入容量" }
                if (index > 0) delay(if (profile == ModelProfile.I5_ZT) timing.i5WriteIntervalMillis else timing.i7WriteIntervalMillis)
                withTimeout(timing.queryTimeoutMillis) { connection.write(query.bytes.copyOf()) }
                val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                    progress.first { it.ended || it.keys.containsAll(query.responseKeys) }
                }
                if (observed?.ended == true) throw LinkEnded()
                val completed = observed != null
                if (!completed) {
                    val error = ControlError(ControlErrorCode.TIMEOUT, "${query.key} 部分字段读取超时")
                    updateAttempt(attempt) { current ->
                        val old = requireNotNull(current.device)
                        val missing = query.expectedFields.filter { old.fields[it] is FieldReading.Loading }.toSet()
                        current.copy(device = old.copy(fieldErrors = old.fieldErrors + missing.associateWith { error }), lastError = error)
                    }
                }
            }
            val device = state.value.device?.takeIf { it.sessionId == attempt.id }
            if (device == null || state.value.connection is ConnectionState.Disconnected) throw LinkEnded()
            if (!progress.value.meaningful) {
                val error = ControlError(ControlErrorCode.TIMEOUT, "没有收到可用的设备状态")
                updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error) }
                return OperationResult.Failure(error)
            }
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Connected(candidate.deviceId, attempt.id, profile)) }
            ready = (state.value.connection as? ConnectionState.Connected)?.sessionId == attempt.id
            if (!ready) throw LinkEnded()
            return OperationResult.Success
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            val error = ControlError(ControlErrorCode.TIMEOUT, "蓝牙连接或读取超时")
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error) }
            return OperationResult.Failure(error)
        } catch (cause: Exception) {
            val code = when (cause) { is IllegalArgumentException -> ControlErrorCode.UNSUPPORTED; else -> ControlErrorCode.DISCONNECTED }
            val error = ControlError(code, cause.message ?: "设备连接已结束")
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error) }
            return OperationResult.Failure(error)
        } finally {
            if (!ready) {
                attempt.observer?.cancel()
                connection?.let { closeSafely(it) }
                withContext(NonCancellable) { lifecycle.withLock { if (active === attempt) active = null } }
            }
        }
    }

    private data class ReadProgress(val keys: Set<String> = emptySet(), val meaningful: Boolean = false, val ended: Boolean = false)

    private class LinkEnded : Exception("设备连接已结束")

    private fun phase(attempt: Attempt, phase: ConnectionPhase) = updateAttempt(attempt) {
        val current = it.connection as? ConnectionState.Connecting
        if (current != null && phase != ConnectionPhase.READY && phase.ordinal >= current.phase.ordinal) {
            it.copy(connection = current.copy(phase = phase))
        } else it
    }

    private inline fun updateAttempt(attempt: Attempt, update: (ControlState) -> ControlState) {
        mutableState.update {
            if (it.device?.sessionId == attempt.id && it.connection !is ConnectionState.Disconnected) update(it) else it
        }
    }

    /** Caller holds lifecycle. Invalidate before closing so old callbacks cannot update a new session. */
    private suspend fun clearActive() {
        val previous = active
        active = null
        generation++
        mutableState.update { it.copy(connection = ConnectionState.Disconnected, device = null) }
        previous?.task?.cancel()
        previous?.observer?.cancel()
        previous?.connection?.let { closeSafely(it) }
    }

    private suspend fun closeSafely(connection: ControlConnection) {
        withContext(NonCancellable) { try { connection.close() } catch (_: Exception) { /* State already records the connection outcome. */ } }
    }

    private fun platformError(): ControlError? = when {
        state.value.platform.permission in setOf(PermissionState.REQUIRED, PermissionState.DENIED) ->
            ControlError(ControlErrorCode.PERMISSION_REQUIRED, "需要蓝牙权限")
        transport == null || state.value.platform.transport != TransportAvailability.Available ->
            ControlError(ControlErrorCode.TRANSPORT_UNAVAILABLE, "平台蓝牙传输不可用")
        else -> null
    }

    private fun failure(error: ControlError): OperationResult.Failure {
        mutableState.update { it.copy(lastError = error) }
        return OperationResult.Failure(error)
    }

    fun updateSettings(settings: UserSettings) {
        val snapshot = settings.snapshot()
        mutableState.update { it.copy(settings = snapshot) }
    }

    fun updatePlatformState(platformState: PlatformState) {
        mutableState.update { it.copy(platform = platformState) }
    }

    private fun UserSettings.snapshot(): UserSettings = copy(islandTriggers = islandTriggers.toSet())
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

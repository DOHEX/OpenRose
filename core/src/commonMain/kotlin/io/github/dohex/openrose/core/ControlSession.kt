package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ProtocolCodec
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelIdentification
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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
    private var operationId = 0L
    private var active: Attempt? = null

    private class Attempt(val id: Long, val candidate: DeviceCandidate) {
        lateinit var task: Deferred<OperationResult>
        var connection: ControlConnection? = null
        var observer: Job? = null
        val codec: ProtocolCodec = createProtocolCodec(requireNotNull(candidate.profile))
        val progress = MutableStateFlow(ReadProgress())
        val commands = Channel<SettingRequest>(32)
        val pending = linkedMapOf<Long, SettingRequest>()
        var commandWorker: Job? = null
        var hasWritten = false
    }

    private data class SettingRequest(val operation: SettingOperation, val result: CompletableDeferred<OperationResult>)

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
                    device = DeviceState(deviceId, next.id, profile, capabilities(profile, null), profile.supportedFields.associateWith { FieldReading.Loading }),
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
            if (!currentCoroutineContext().isActive) throw cancelled
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

    /** A caller owns its wait; the platform session owns the queued operation. */
    suspend fun set(setting: DeviceSetting, value: FieldValue): OperationResult {
        platformError()?.let { return failure(it) }
        val snapshot = value.snapshot()
        val request = lifecycle.withLock {
            val attempt = active
            if (attempt == null || state.value.connection !is ConnectionState.Connected) {
                return failure(ControlError(ControlErrorCode.NOT_READY, "设备尚未就绪"))
            }
            if (setting !in requireNotNull(state.value.device).capabilities.writableSettings) {
                return failure(ControlError(ControlErrorCode.UNSUPPORTED, "${setting.name} 尚不支持写入"))
            }
            // Validate before submission without allocating codec sequence numbers.
            validateSetting(requireNotNull(attempt.candidate.profile), setting, snapshot)?.let { return failure(it) }
            if (attempt.pending.size >= 32) return failure(ControlError(ControlErrorCode.NOT_READY, "设置队列已满"))
            val operation = SettingOperation(++operationId, attempt.id, setting, snapshot, SettingStage.SUBMITTED)
            val next = SettingRequest(operation, CompletableDeferred())
            attempt.pending[operation.id] = next
            mutableState.update { it.copy(settingOperations = (it.settingOperations + operation).takeLast(64), lastError = null) }
            check(attempt.commands.trySend(next).isSuccess)
            next
        }
        return request.result.await()
    }

    private fun validateSetting(profile: ModelProfile, setting: DeviceSetting, value: FieldValue): ControlError? {
        val noise = noiseControlCapabilities(profile, firmware())
        val valid = when (setting) {
            DeviceSetting.GAME_MODE -> value is FieldValue.Toggle
            DeviceSetting.EQ -> {
                if (value is FieldValue.Text && value.value !in equalizerCapabilities(profile, firmware()).presets) {
                    return ControlError(ControlErrorCode.UNSUPPORTED, "该型号与固件不支持 ${value.value} EQ")
                }
                value is FieldValue.Text
            }
            DeviceSetting.NOISE_MODE -> {
                if (value is FieldValue.Text && value.value !in noise.modes) {
                    return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持 ${value.value} 模式")
                }
                value is FieldValue.Text
            }
            DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL -> value is FieldValue.Number && value.value in noise.levels
            DeviceSetting.NOISE_CYCLE -> value is FieldValue.NoiseCycle && value.flags.keys == noise.cycleModes &&
                value.flags.values.all { it != null } && value.flags.values.count { it == true } >= noise.minimumCycleSelections
            else -> return ControlError(ControlErrorCode.UNSUPPORTED, "${setting.name} 尚不支持写入")
        }
        return if (valid) null else ControlError(ControlErrorCode.INVALID_ARGUMENT, "${setting.name} 参数无效")
    }

    private suspend fun runSettings(attempt: Attempt) {
        for (request in attempt.commands) {
            if (request.result.isCompleted) continue
            val result = try {
                applySetting(attempt, request)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                settingFailure(attempt, request, ControlError(ControlErrorCode.TIMEOUT, "设置或设备回读超时"), SettingStage.TIMED_OUT)
            } catch (cause: Exception) {
                val code = when (cause) {
                    is LinkEnded -> ControlErrorCode.DISCONNECTED
                    is UnsupportedOperationException -> ControlErrorCode.UNSUPPORTED
                    is IllegalArgumentException -> ControlErrorCode.INVALID_ARGUMENT
                    else -> ControlErrorCode.FAILED
                }
                settingFailure(attempt, request, ControlError(code, cause.message ?: "设置失败"),
                    if (code == ControlErrorCode.DISCONNECTED) SettingStage.DISCONNECTED else SettingStage.FAILED)
            }
            lifecycle.withLock {
                attempt.pending.remove(request.operation.id)
                request.result.complete(result)
                val error = (result as? OperationResult.Failure)?.error
                if (active === attempt && error?.code in setOf(ControlErrorCode.TIMEOUT, ControlErrorCode.FAILED)) {
                    // Neither protocol correlates a late readback with a transaction. End this
                    // link before another setting can consume an old response or native write.
                    clearActive()
                    mutableState.update { it.copy(lastError = error) }
                }
            }
        }
    }

    private suspend fun applySetting(attempt: Attempt, request: SettingRequest): OperationResult {
        val link = attempt.connection ?: throw LinkEnded()
        val operation = request.operation
        val prepared = attempt.codec.prepareSetting(operation.setting, operation.requestedValue, firmware())
        if ((prepared.writeFrames + listOf(prepared.readback.bytes)).any { it.size > link.maxWriteSize }) {
            throw UnsupportedOperationException("完整协议设置或回读超出传输写入容量")
        }
        for (frame in prepared.writeFrames) writePaced(attempt, link, frame)
        settingStage(attempt, request, SettingStage.WRITTEN)
        // Take the baseline after pacing so a setter ACK cannot satisfy the readback.
        pace(attempt)
        ensureActive(attempt)
        val baseline = attempt.progress.value
        withTimeout(timing.queryTimeoutMillis) { link.write(prepared.readback.bytes.copyOf()) }
        val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
            attempt.progress.first { progress ->
                progress.ended || (prepared.readback.responseKeys.all { (progress.keyRevisions[it] ?: 0) > (baseline.keyRevisions[it] ?: 0) } &&
                    prepared.expectedValues.keys.all { field ->
                        val value = (state.value.device?.fields?.get(field) as? FieldReading.Known)?.value
                        (progress.fieldRevisions[field] ?: 0) > (baseline.fieldRevisions[field] ?: 0) &&
                            value != null && (value !is FieldValue.NoiseCycle || value.flags.values.all { it != null })
                    })
            }
        }
        if (observed?.ended == true) throw LinkEnded()
        ensureActive(attempt)
        if (observed == null) return settingFailure(attempt, request, ControlError(ControlErrorCode.TIMEOUT, "未收到有效的设置回读"), SettingStage.TIMED_OUT)
        val actual = requireNotNull(state.value.device).fields
        return if (prepared.expectedValues.all { (field, expected) -> (actual[field] as? FieldReading.Known)?.value == expected }) {
            settingStage(attempt, request, SettingStage.CONFIRMED)
            OperationResult.Success
        } else settingFailure(attempt, request, ControlError(ControlErrorCode.NOT_APPLIED, "设备回读与请求不一致"), SettingStage.NOT_APPLIED)
    }

    private fun ensureActive(attempt: Attempt) {
        if (state.value.device?.sessionId != attempt.id || state.value.connection is ConnectionState.Disconnected || attempt.progress.value.ended) throw LinkEnded()
    }

    private suspend fun pace(attempt: Attempt) {
        if (attempt.hasWritten) delay(if (attempt.candidate.profile == ModelProfile.I5_ZT) timing.i5WriteIntervalMillis else timing.i7WriteIntervalMillis)
        attempt.hasWritten = true
    }

    private suspend fun writePaced(attempt: Attempt, link: ControlConnection, bytes: ByteArray) {
        check(bytes.size <= link.maxWriteSize) { "完整协议报文超出传输写入容量" }
        pace(attempt)
        ensureActive(attempt)
        withTimeout(timing.queryTimeoutMillis) { link.write(bytes.copyOf()) }
    }

    private fun settingStage(attempt: Attempt, request: SettingRequest, stage: SettingStage, error: ControlError? = null) {
        mutableState.update { current ->
            if (current.device?.sessionId != attempt.id || request.result.isCompleted) current
            else current.copy(settingOperations = current.settingOperations.map {
                if (it.id == request.operation.id) it.copy(stage = stage, error = error) else it
            }, lastError = error)
        }
    }

    private fun settingFailure(attempt: Attempt, request: SettingRequest, error: ControlError, stage: SettingStage): OperationResult.Failure {
        settingStage(attempt, request, stage, error)
        return OperationResult.Failure(error)
    }

    /** Caller holds lifecycle; complete every waiter before cancelling the owned worker. */
    private fun endSettings(attempt: Attempt) {
        val error = ControlError(ControlErrorCode.DISCONNECTED, "连接会话已结束")
        val ids = attempt.pending.keys.toSet()
        mutableState.update { current -> current.copy(settingOperations = current.settingOperations.map {
            if (it.id in ids) it.copy(stage = SettingStage.DISCONNECTED, error = error) else it
        }) }
        attempt.pending.values.forEach { it.result.complete(OperationResult.Failure(error)) }
        attempt.pending.clear()
        attempt.commands.close()
        attempt.commandWorker?.cancel()
    }

    private fun firmware(): FieldValue.Firmware? = (state.value.device?.fields?.get(DeviceField.FIRMWARE) as? FieldReading.Known)?.value as? FieldValue.Firmware

    private fun capabilities(profile: ModelProfile, firmware: FieldValue.Firmware?) = DeviceCapabilities(
        profile.supportedFields,
        setOf(DeviceSetting.NOISE_MODE, DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL, DeviceSetting.NOISE_CYCLE, DeviceSetting.EQ, DeviceSetting.GAME_MODE),
        noiseControlCapabilities(profile, firmware),
        equalizerCapabilities(profile, firmware),
    )

    private fun FieldValue.snapshot(): FieldValue = when (this) {
        is FieldValue.NoiseCycle -> copy(flags = flags.toMap())
        is FieldValue.IntegerList -> copy(values = values.toList())
        is FieldValue.Firmware -> copy(parts = parts.toList())
        is FieldValue.TouchKeys -> copy(bindings = bindings.toList())
        else -> this
    }

    private suspend fun openAndRead(attempt: Attempt): OperationResult {
        val candidate = attempt.candidate
        val profile = requireNotNull(candidate.profile)
        val codec = attempt.codec
        var connection: ControlConnection? = null
        var ready = false
        try {
            val gatt = profile.gatt
            withTimeout(timing.connectTimeoutMillis) {
                // Keep an acquired handle visible to cleanup even if timeout wins result delivery.
                connection = requireNotNull(transport).connect(candidate.deviceId, TransportEndpoint.BleGatt(gatt.serviceUuid, gatt.writeUuid, gatt.notifyUuid)) { phase ->
                    phase(attempt, phase)
                }
            }
            val opened = requireNotNull(connection)
            lifecycle.withLock {
                if (active !== attempt) throw CancellationException("Stale connection")
                attempt.connection = opened
            }
            val progress = attempt.progress
            val link = opened
            attempt.observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var error = ControlError(ControlErrorCode.DISCONNECTED, "设备已断开")
                try {
                    link.notifications.collect { bytes ->
                        val parsed = codec.feedNotification(bytes.copyOf())
                        updateAttempt(attempt) { current ->
                            val old = requireNotNull(current.device)
                            val readings = parsed.flatMap { it.readings.entries }.associate { it.toPair() }
                            val firmware = ((readings[DeviceField.FIRMWARE] ?: old.fields[DeviceField.FIRMWARE]) as? FieldReading.Known)?.value as? FieldValue.Firmware
                            current.copy(device = old.copy(fields = old.fields + readings,
                                capabilities = capabilities(profile, firmware),
                                fieldErrors = old.fieldErrors - readings.keys,
                                rawNotifications = (old.rawNotifications + bytes.hex()).takeLast(128),
                                unknownNotifications = (old.unknownNotifications + parsed.filter { !it.accepted }.map { it.rawHex }).takeLast(128),
                                warnings = (old.warnings + parsed.flatMap { it.warnings }).takeLast(128)))
                        }
                        progress.update { previous ->
                            val keys = parsed.flatMap { it.responseKeys }.toSet()
                            val fields = parsed.flatMap { it.readings.keys }.toSet()
                            previous.copy(keys = previous.keys + keys, meaningful = previous.meaningful || parsed.any { it.accepted },
                                keyRevisions = previous.keyRevisions + keys.associateWith { (previous.keyRevisions[it] ?: 0) + 1 },
                                fieldRevisions = previous.fieldRevisions + fields.associateWith { (previous.fieldRevisions[it] ?: 0) + 1 })
                        }
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
                                endSettings(attempt)
                                active = null
                                updateAttempt(attempt) { if (it.connection is ConnectionState.Disconnected) it else it.copy(connection = ConnectionState.Disconnected, device = null, lastError = error) }
                            }
                        }
                    }
                    closeSafely(link)
                }
            }
            phase(attempt, ConnectionPhase.SUBSCRIBING)
            withTimeout(timing.connectTimeoutMillis) { opened.subscribe() }
            phase(attempt, ConnectionPhase.INITIALIZING)
            val queries = codec.initQueries(opened.maxWriteSize)
            for (query in queries) {
                writePaced(attempt, opened, query.bytes)
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
            attempt.commandWorker = scope.launch { runSettings(attempt) }
            return OperationResult.Success
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) {
                updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, device = null,
                    lastError = ControlError(ControlErrorCode.DISCONNECTED, "控制会话已结束")) }
                throw cancelled
            }
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

    private data class ReadProgress(
        val keys: Set<String> = emptySet(),
        val meaningful: Boolean = false,
        val ended: Boolean = false,
        val keyRevisions: Map<String, Long> = emptyMap(),
        val fieldRevisions: Map<DeviceField, Long> = emptyMap(),
    )

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
        previous?.let { endSettings(it) }
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

package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ProtocolQuery
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
import io.github.dohex.openrose.core.transport.TransportFacts
import io.github.dohex.openrose.core.transport.TransportSelection
import io.github.dohex.openrose.core.transport.TransportSelectionReason
import io.github.dohex.openrose.core.transport.AudioProfileEvidence
import io.github.dohex.openrose.core.transport.RecoverableTransportException
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import io.github.dohex.openrose.core.transport.TerminalTransportException
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

    private class Attempt(val id: Long, val candidate: DeviceCandidate, val forceBle: Boolean) {
        lateinit var task: Deferred<OperationResult>
        var connection: ControlConnection? = null
        var observer: Job? = null
        var codec: ProtocolCodec = createProtocolCodec(requireNotNull(candidate.profile))
        var progress = MutableStateFlow(ReadProgress())
        val linkEpoch = MutableStateFlow(0L)
        var selection: TransportSelection? = null
        var initializationQueryIndex: Int? = null
        val commands = Channel<SettingRequest>(32)
        val pending = linkedMapOf<Long, SettingRequest>()
        var commandWorker: Job? = null
        var hasWritten = false
        var recoveryUsed = false
        var ready = false
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
                    val manufacturerDataHex = ModelIdentification.normalizedHex(advertisement.manufacturerData)
                    val profile = ModelIdentification.identify(advertisement.manufacturerData)
                    val candidate = DeviceCandidate(advertisement.deviceId, advertisement.name, advertisement.rssi,
                        profile, manufacturerDataHex, ModelIdentification.classicAddress(manufacturerDataHex, profile))
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
            val next = Attempt(++generation, candidate, state.value.settings.forceBle)
            val profile = requireNotNull(candidate.profile)
            mutableState.update {
                it.copy(connection = ConnectionState.Connecting(deviceId, next.id, ConnectionPhase.CONNECTING),
                    device = DeviceState(deviceId, next.id, profile, capabilities(profile, null), profile.supportedFields.associateWith { FieldReading.Loading }),
                    lastError = null, transportSelection = null, connectionRecovery = null)
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
            mutableState.update { it.copy(connection = ConnectionState.Disconnected, device = null, lastError = null, connectionRecovery = null) }
        }
    }

    /** Reads a detached snapshot only when the reference still identifies the ready device session. */
    fun readSession(reference: SessionReference): SessionSnapshotResult {
        val captured = mutableState.value
        val connection = captured.connection as? ConnectionState.Connected
            ?: return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "设备会话尚未就绪"))
        val device = captured.device
        if (device == null || device.deviceId != connection.deviceId || device.sessionId != connection.sessionId ||
            device.profile != connection.profile) {
            return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.NOT_READY, "当前设备状态与连接会话不一致"))
        }
        if (reference.deviceId != connection.deviceId || reference.sessionId != connection.sessionId) {
            return SessionSnapshotResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
        }
        return SessionSnapshotResult.Available(device.snapshot())
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
            DeviceSetting.CUSTOM_EQ -> {
                val custom = equalizerCapabilities(profile, firmware()).custom
                    ?: return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持自定义均衡器")
                value is FieldValue.IntegerList && value.values.size == custom.bandsHz.size && value.values.all { it in custom.minGain..custom.maxGain }
            }
            DeviceSetting.GAIN -> value is FieldValue.Number && value.value in gainLevels(profile, firmware())
            DeviceSetting.HEARING_PROTECTION -> value is FieldValue.Number && value.value in hearingProtectionLevels(profile, firmware())
            DeviceSetting.PROMPT_VOLUME -> {
                val levels = promptVolumeLevels(profile, firmware())
                if (profile == ModelProfile.I7_RS_V2 && value is FieldValue.Number && value.value == 0 && value.value !in levels) {
                    return ControlError(ControlErrorCode.UNSUPPORTED, "当前固件不支持关闭提示音")
                }
                value is FieldValue.Number && value.value in levels
            }
            DeviceSetting.TOUCH_BINDING -> {
                if (value !is FieldValue.TouchAssignment) return ControlError(ControlErrorCode.INVALID_ARGUMENT, "触控绑定需要作用耳、手势和动作")
                val touch = touchControlCapabilities(profile)
                if (TouchSlot(value.side, value.gesture) !in touch.slots) return ControlError(ControlErrorCode.INVALID_ARGUMENT, "触控作用耳或手势无效")
                if (value.action !in touch.actions) return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持 ${value.action} 触控动作")
                true
            }
            DeviceSetting.GAME_MODE, DeviceSetting.MULTIPOINT, DeviceSetting.TOUCH_ENABLED, DeviceSetting.EAR_DETECTION -> value is FieldValue.Toggle
            DeviceSetting.AUDIO_MODE -> {
                if (value is FieldValue.Text && value.value !in audioControlCapabilities(profile).modes) {
                    return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持 ${value.value} 音频配置")
                }
                value is FieldValue.Text
            }
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
        settingStage(attempt, request, SettingStage.SUBMITTED, totalFrames = prepared.writeFrames.size)
        if ((prepared.writeFrames + prepared.readbacks.map { it.bytes }).any { it.size > link.maxWriteSize }) {
            throw UnsupportedOperationException("完整协议设置或回读超出传输写入容量")
        }
        for ((index, frame) in prepared.writeFrames.withIndex()) {
            writePaced(attempt, link, frame)
            settingStage(attempt, request,
                if (index == prepared.writeFrames.lastIndex) SettingStage.WRITTEN else SettingStage.PARTIALLY_WRITTEN,
                writtenFrames = index + 1)
        }
        val baselines = mutableListOf<Pair<ProtocolQuery, ReadProgress>>()
        var actualValues = emptyMap<DeviceField, FieldValue>()
        for (query in prepared.readbacks) {
            // A later field query gets its own baseline; earlier unsolicited data cannot confirm it.
            pace(attempt)
            ensureActive(attempt)
            baselines += query to attempt.progress.value
            withTimeout(timing.queryTimeoutMillis) { link.write(query.bytes.copyOf()) }
            val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                attempt.progress.first { progress ->
                    if (progress.ended) true else {
                        val fields = state.value.device?.fields.orEmpty()
                        val values = prepared.expectedValues.mapNotNull { (field, expected) ->
                            val reading = baselines.lastOrNull { field in it.first.expectedFields } ?: return@mapNotNull null
                            val (readback, baseline) = reading
                            val fresh = (progress.fieldRevisions[field] ?: 0) > (baseline.fieldRevisions[field] ?: 0) &&
                                readback.responseKeys.all { (progress.keyRevisions[it] ?: 0) > (baseline.keyRevisions[it] ?: 0) }
                            if (fresh) confirmationValue(fields[field], expected)?.let { field to it } else null
                        }.toMap()
                        val confirmed = values.filter { (field, value) -> value == prepared.expectedValues[field] }.keys.toSet()
                        settingStage(attempt, request, SettingStage.WRITTEN, confirmedFields = confirmed)
                        val required = baselines.flatMap { it.first.expectedFields }.filter { it in prepared.expectedValues }.toSet()
                        if (values.keys.containsAll(required)) {
                            actualValues = values
                            true
                        } else false
                    }
                }
            }
            if (observed?.ended == true) throw LinkEnded()
            ensureActive(attempt)
            if (observed == null) return settingFailure(attempt, request,
                ControlError(ControlErrorCode.TIMEOUT, "未收到有效的设置回读"), SettingStage.TIMED_OUT)
        }
        return if (prepared.expectedValues.all { (field, expected) -> actualValues[field] == expected }) {
            settingStage(attempt, request, SettingStage.CONFIRMED)
            OperationResult.Success
        } else settingFailure(attempt, request, ControlError(ControlErrorCode.NOT_APPLIED, "设备回读与请求不一致"), SettingStage.NOT_APPLIED)
    }

    /** A touch assignment confirms only its target; the device still retains its full bindings. */
    private fun confirmationValue(reading: FieldReading?, expected: FieldValue): FieldValue? {
        val actual = (reading as? FieldReading.Known)?.value ?: return null
        return when {
            expected is FieldValue.TouchAssignment -> {
                val binding = (actual as? FieldValue.TouchKeys)?.bindings?.firstOrNull {
                    it.side == expected.side && it.gesture == expected.gesture
                }
                binding?.action?.let { expected.copy(action = it) }
            }
            actual is FieldValue.NoiseCycle && actual.flags.values.any { it == null } -> null
            else -> actual
        }
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

    private fun settingStage(
        attempt: Attempt,
        request: SettingRequest,
        stage: SettingStage,
        error: ControlError? = null,
        writtenFrames: Int? = null,
        totalFrames: Int? = null,
        confirmedFields: Set<DeviceField>? = null,
    ) {
        mutableState.update { current ->
            if (current.device?.sessionId != attempt.id || request.result.isCompleted) current
            else current.copy(settingOperations = current.settingOperations.map {
                if (it.id == request.operation.id) it.copy(stage = stage, error = error,
                    writtenFrames = writtenFrames ?: it.writtenFrames, totalFrames = totalFrames ?: it.totalFrames,
                    confirmedFields = confirmedFields ?: it.confirmedFields) else it
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
        readableFields = profile.supportedFields,
        writableSettings = setOf(DeviceSetting.NOISE_MODE, DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL,
            DeviceSetting.NOISE_CYCLE, DeviceSetting.EQ, DeviceSetting.GAME_MODE, DeviceSetting.AUDIO_MODE, DeviceSetting.MULTIPOINT, DeviceSetting.TOUCH_BINDING, DeviceSetting.TOUCH_ENABLED, DeviceSetting.EAR_DETECTION) +
            setOf(DeviceSetting.PROMPT_VOLUME) +
            (if (profile == ModelProfile.I7_RS_V2) setOf(DeviceSetting.CUSTOM_EQ) else emptySet()) +
            (if (gainLevels(profile, firmware).isNotEmpty()) setOf(DeviceSetting.GAIN) else emptySet()) +
            (if (hearingProtectionLevels(profile, firmware).isNotEmpty()) setOf(DeviceSetting.HEARING_PROTECTION) else emptySet()),
        noiseControl = noiseControlCapabilities(profile, firmware),
        equalizer = equalizerCapabilities(profile, firmware),
        touchControl = touchControlCapabilities(profile),
        audioControl = audioControlCapabilities(profile),
        promptVolumeLevels = promptVolumeLevels(profile, firmware),
        gainLevels = gainLevels(profile, firmware),
        hearingProtectionLevels = hearingProtectionLevels(profile, firmware),
    )

    private fun FieldValue.snapshot(): FieldValue = when (this) {
        is FieldValue.NoiseCycle -> copy(flags = flags.toMap())
        is FieldValue.IntegerList -> copy(values = values.toList())
        is FieldValue.Firmware -> copy(parts = parts.toList())
        is FieldValue.TouchKeys -> copy(bindings = bindings.toList())
        else -> this
    }

    private fun DeviceState.snapshot() = copy(
        capabilities = capabilities.snapshot(),
        fields = fields.mapValues { (_, reading) -> reading.snapshot() },
        fieldErrors = fieldErrors.toMap(),
        rawNotifications = rawNotifications.toList(),
        unknownNotifications = unknownNotifications.toList(),
        warnings = warnings.toList(),
    )

    private fun FieldReading.snapshot(): FieldReading = when (this) {
        FieldReading.Loading -> this
        is FieldReading.NotProvided -> copy()
        is FieldReading.Unknown -> copy()
        is FieldReading.Known -> copy(value = value.snapshot())
    }

    private fun DeviceCapabilities.snapshot() = copy(
        readableFields = readableFields.toSet(),
        writableSettings = writableSettings.toSet(),
        noiseControl = noiseControl?.copy(
            modes = noiseControl.modes.toSet(),
            levels = noiseControl.levels.toSet(),
            cycleModes = noiseControl.cycleModes.toSet(),
        ),
        equalizer = equalizer?.copy(
            presets = equalizer.presets.toSet(),
            custom = equalizer.custom?.copy(bandsHz = equalizer.custom.bandsHz.toList()),
        ),
        touchControl = touchControl?.copy(
            slots = touchControl.slots.toSet(),
            actions = touchControl.actions.toSet(),
        ),
        audioControl = audioControl?.copy(modes = audioControl.modes.toSet()),
        promptVolumeLevels = promptVolumeLevels.toSet(),
        gainLevels = gainLevels.toSet(),
        hearingProtectionLevels = hearingProtectionLevels.toSet(),
    )

    private suspend fun openAndRead(attempt: Attempt): OperationResult {
        try {
            val selected = attempt.selection ?: selectTransport(attempt).also { attempt.selection = it }
            updateAttempt(attempt) { it.copy(transportSelection = selected) }
            while (true) {
                val epoch = beginLink(attempt)
                try {
                    return openAndReadLink(attempt, selected, epoch)
                } catch (cause: RecoverableTransportException) {
                    if (attempt.recoveryUsed || !canRetry(attempt, selected, cause)) throw cause
                    attempt.recoveryUsed = true
                    prepareRecovery(attempt, selected, cause, epoch)
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) {
                updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, device = null,
                    lastError = ControlError(ControlErrorCode.DISCONNECTED, "控制会话已结束"), transportSelection = null) }
                throw cancelled
            }
            val error = ControlError(ControlErrorCode.TIMEOUT, "蓝牙连接或读取超时")
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error, transportSelection = null) }
            return OperationResult.Failure(error)
        } catch (cause: Exception) {
            val code = when {
                cause is UnsupportedOperationException || cause is IllegalArgumentException -> ControlErrorCode.UNSUPPORTED
                cause is SecurityException -> ControlErrorCode.PERMISSION_REQUIRED
                cause is RecoverableTransportException && cause.cause is UnsupportedOperationException -> ControlErrorCode.UNSUPPORTED
                cause is RecoverableTransportException && cause.cause is IllegalArgumentException -> ControlErrorCode.UNSUPPORTED
                cause is RecoverableTransportException && cause.cause is SecurityException -> ControlErrorCode.PERMISSION_REQUIRED
                cause is RecoverableTransportException && cause.reason == TransportRecoveryReason.NO_INITIAL_RESPONSE -> ControlErrorCode.TIMEOUT
                cause is RecoverableTransportException && cause.cause is TimeoutCancellationException -> ControlErrorCode.TIMEOUT
                else -> ControlErrorCode.DISCONNECTED
            }
            val error = ControlError(code, cause.message ?: "设备连接已结束")
            updateAttempt(attempt) { it.copy(connection = ConnectionState.Disconnected, lastError = error, transportSelection = null) }
            return OperationResult.Failure(error)
        } finally {
            if (!attempt.ready) {
                closeAttemptLink(attempt)
                withContext(NonCancellable) {
                    lifecycle.withLock {
                        if (active === attempt) {
                            active = null
                            mutableState.update { it.copy(transportSelection = null) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun beginLink(attempt: Attempt): Long = lifecycle.withLock {
        if (active !== attempt) throw CancellationException("Stale connection")
        attempt.linkEpoch.value += 1
        attempt.codec = createProtocolCodec(requireNotNull(attempt.candidate.profile))
        attempt.progress = MutableStateFlow(ReadProgress())
        attempt.hasWritten = false
        attempt.initializationQueryIndex = null
        attempt.connection = null
        attempt.observer = null
        attempt.ready = false
        attempt.linkEpoch.value
    }

    private suspend fun openAndReadLink(attempt: Attempt, selection: TransportSelection, epoch: Long): OperationResult {
        val candidate = attempt.candidate
        val profile = requireNotNull(candidate.profile)
        val codec = attempt.codec
        val progress = attempt.progress
        val opened = withTimeout(timing.connectTimeoutMillis) {
            requireNotNull(transport).connect(candidate.deviceId, selection.endpoint) { nextPhase ->
                phase(attempt, epoch, nextPhase)
            }.also { attempt.connection = it }
        }
        lifecycle.withLock {
            if (active !== attempt || attempt.linkEpoch.value != epoch) throw CancellationException("Stale connection")
            attempt.connection = opened
        }
        val link = opened
        attempt.observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable? = null
            var error = ControlError(ControlErrorCode.DISCONNECTED, "设备已断开")
            try {
                link.notifications.collect { bytes ->
                    if (attempt.linkEpoch.value != epoch) return@collect
                    val parsed = codec.feedNotification(bytes.copyOf())
                    updateLinkAttempt(attempt, epoch) { current ->
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
                failure = cause
                error = when (cause) {
                    is UnsupportedOperationException, is IllegalArgumentException -> ControlError(ControlErrorCode.UNSUPPORTED, cause.message ?: "不支持该连接")
                    is SecurityException -> ControlError(ControlErrorCode.PERMISSION_REQUIRED, cause.message ?: "蓝牙权限不足")
                    else -> ControlError(ControlErrorCode.DISCONNECTED, cause.message ?: "设备已断开")
                }
            } finally {
                withContext(NonCancellable) {
                    lifecycle.withLock {
                        // Publish link termination under the same lock as the READY commit so a
                        // final notification cannot race into a dead Connected state.
                        progress.update { it.copy(ended = true, failure = failure) }
                        if (active === attempt && attempt.linkEpoch.value == epoch) {
                            val setupRecovery = failure is RecoverableTransportException && !attempt.ready &&
                                selection.endpoint is TransportEndpoint.BleGatt
                            if (!setupRecovery) {
                                endSettings(attempt)
                                active = null
                                updateLinkAttempt(attempt, epoch) {
                                    if (it.connection is ConnectionState.Disconnected) it
                                    else it.copy(connection = ConnectionState.Disconnected, device = null, lastError = error, transportSelection = null)
                                }
                            }
                        }
                    }
                }
                closeSafely(link)
            }
        }
        phase(attempt, epoch, ConnectionPhase.SUBSCRIBING)
        withTimeout(timing.connectTimeoutMillis) { opened.subscribe() }
        throwProgressFailure(progress)
        phase(attempt, epoch, ConnectionPhase.INITIALIZING)
        val queries = codec.initQueries(opened.maxWriteSize)
        for ((index, query) in queries.withIndex()) {
            attempt.initializationQueryIndex = index
            throwProgressFailure(progress)
            writeInitialQuery(attempt, selection, opened, query.bytes, index)
            val waitForMeaningfulState = index == 0 && selection.endpoint is TransportEndpoint.BleGatt &&
                requireNotNull(transport).supportsInitializationRecovery
            val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                progress.first {
                    it.ended || (it.keys.containsAll(query.responseKeys) && (!waitForMeaningfulState || it.meaningful))
                }
            }
            if (observed?.ended == true) throw observed.failure ?: LinkEnded()
            if (observed == null) {
                if (index == 0 && !progress.value.meaningful && selection.endpoint is TransportEndpoint.BleGatt &&
                    requireNotNull(transport).supportsInitializationRecovery) {
                    throw RecoverableTransportException(TransportRecoveryReason.NO_INITIAL_RESPONSE, "首条初始化读取没有收到有效设备状态")
                }
                val timeout = ControlError(ControlErrorCode.TIMEOUT, "${query.key} 部分字段读取超时")
                updateLinkAttempt(attempt, epoch) { current ->
                    val old = requireNotNull(current.device)
                    val missing = query.expectedFields.filter { old.fields[it] is FieldReading.Loading }.toSet()
                    current.copy(device = old.copy(fieldErrors = old.fieldErrors + missing.associateWith { timeout }), lastError = timeout)
                }
            }
        }
        val device = state.value.device?.takeIf { it.sessionId == attempt.id }
        if (device == null || state.value.connection is ConnectionState.Disconnected) throw LinkEnded()
        if (!progress.value.meaningful) {
            val timeout = ControlError(ControlErrorCode.TIMEOUT, "没有收到可用的设备状态")
            updateLinkAttempt(attempt, epoch) { it.copy(connection = ConnectionState.Disconnected, lastError = timeout, transportSelection = null) }
            return OperationResult.Failure(timeout)
        }
        val committedReady = lifecycle.withLock {
            if (active !== attempt || attempt.linkEpoch.value != epoch || progress.value.ended ||
                state.value.device?.sessionId != attempt.id || state.value.connection is ConnectionState.Disconnected) {
                false
            } else {
                mutableState.update { it.copy(connection = ConnectionState.Connected(candidate.deviceId, attempt.id, profile)) }
                attempt.ready = (state.value.connection as? ConnectionState.Connected)?.sessionId == attempt.id
                attempt.ready
            }
        }
        if (!committedReady) throw progress.value.failure ?: LinkEnded()
        attempt.commandWorker = scope.launch { runSettings(attempt) }
        return OperationResult.Success
    }

    private suspend fun writeInitialQuery(
        attempt: Attempt,
        selection: TransportSelection,
        connection: ControlConnection,
        bytes: ByteArray,
        queryIndex: Int,
    ) {
        try {
            writePaced(attempt, connection, bytes)
        } catch (cause: CancellationException) {
            if (cause is TimeoutCancellationException && currentCoroutineContext().isActive &&
                queryIndex == 0 && canRecoverUnclassified(attempt, selection)) {
                throw RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, "首条初始化读取写入超时", cause)
            }
            throw cause
        } catch (cause: Exception) {
            if (queryIndex == 0 && canRecoverUnclassified(attempt, selection) && cause !is SecurityException &&
                cause !is IllegalArgumentException && cause !is UnsupportedOperationException && cause !is LinkEnded &&
                cause !is RecoverableTransportException && cause !is TerminalTransportException) {
                throw RecoverableTransportException(TransportRecoveryReason.WRITE_FAILED, "首条初始化读取写入失败", cause)
            }
            throw cause
        }
    }

    private fun canRecoverUnclassified(attempt: Attempt, selection: TransportSelection): Boolean =
        selection.endpoint is TransportEndpoint.BleGatt && requireNotNull(transport).supportsInitializationRecovery

    private fun canRetry(
        attempt: Attempt,
        selection: TransportSelection,
        failure: RecoverableTransportException,
    ): Boolean {
        if (selection.endpoint !is TransportEndpoint.BleGatt) return false
        if (failure.cause is SecurityException || failure.cause is IllegalArgumentException ||
            failure.cause is UnsupportedOperationException || failure.cause is TerminalTransportException) return false
        return when (failure.reason) {
            TransportRecoveryReason.WRITE_FAILED, TransportRecoveryReason.NO_INITIAL_RESPONSE ->
                attempt.initializationQueryIndex == 0 && canRecoverUnclassified(attempt, selection)
            TransportRecoveryReason.SERVICE_DISCOVERY,
            TransportRecoveryReason.SERVICES_UNAVAILABLE,
            TransportRecoveryReason.SUBSCRIPTION,
            TransportRecoveryReason.SERVICES_CHANGED -> true
        }
    }

    private fun throwProgressFailure(progress: MutableStateFlow<ReadProgress>) {
        val current = progress.value
        if (current.ended) throw current.failure ?: LinkEnded()
    }

    private suspend fun prepareRecovery(
        attempt: Attempt,
        selection: TransportSelection,
        failure: RecoverableTransportException,
        failedEpoch: Long,
    ) {
        val (oldConnection, oldObserver) = lifecycle.withLock {
            if (active !== attempt || attempt.linkEpoch.value != failedEpoch) throw CancellationException("Stale recovery")
            // Invalidate callbacks before cancellation so an old finally cannot clear the new link.
            attempt.linkEpoch.value += 1
            val oldConnection = attempt.connection
            val oldObserver = attempt.observer
            attempt.connection = null
            attempt.observer = null
            oldConnection to oldObserver
        }
        withContext(NonCancellable) {
            oldObserver?.cancel()
            try {
                oldConnection?.close()
            } finally {
                oldObserver?.join()
            }
        }
        lifecycle.withLock {
            if (active !== attempt) throw CancellationException("Stale recovery")
            attempt.codec = createProtocolCodec(requireNotNull(attempt.candidate.profile))
            attempt.progress = MutableStateFlow(ReadProgress())
            attempt.hasWritten = false
            attempt.initializationQueryIndex = null
            attempt.ready = false
            val profile = requireNotNull(attempt.candidate.profile)
            mutableState.update { current ->
                if (current.device?.sessionId != attempt.id || current.connection is ConnectionState.Disconnected) current
                else current.copy(
                    connection = ConnectionState.Connecting(attempt.candidate.deviceId, attempt.id, ConnectionPhase.CONNECTING),
                    device = DeviceState(attempt.candidate.deviceId, attempt.id, profile, capabilities(profile, null),
                        profile.supportedFields.associateWith { FieldReading.Loading }),
                    lastError = null,
                    transportSelection = selection,
                    connectionRecovery = ConnectionRecovery(attempt.id, failure.reason),
                )
            }
        }
    }

    private suspend fun closeAttemptLink(attempt: Attempt) = withContext(NonCancellable) {
        val (oldConnection, oldObserver) = lifecycle.withLock {
            attempt.linkEpoch.value += 1
            val oldConnection = attempt.connection
            val oldObserver = attempt.observer
            attempt.connection = null
            attempt.observer = null
            oldConnection to oldObserver
        }
        oldObserver?.cancel()
        oldConnection?.let { closeSafely(it) }
        oldObserver?.join()
    }

    private suspend fun selectTransport(attempt: Attempt): TransportSelection {
        val profile = requireNotNull(attempt.candidate.profile)
        val gatt = profile.gatt
        val ble = TransportEndpoint.BleGatt(gatt.serviceUuid, gatt.writeUuid, gatt.notifyUuid)
        if (profile != ModelProfile.I7_RS_V2) return TransportSelection(ble, TransportSelectionReason.MODEL_BLE_ONLY)
        if (attempt.forceBle) return TransportSelection(ble, TransportSelectionReason.FORCED_BLE)
        val address = attempt.candidate.classicAddress
            ?: return TransportSelection(ble, TransportSelectionReason.CLASSIC_ADDRESS_UNAVAILABLE)

        val facts = try {
            withTimeout(timing.connectTimeoutMillis) {
                requireNotNull(transport).connectionFacts(attempt.candidate.deviceId, address)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TransportFacts()
        }
        if (!facts.sppAvailable) return TransportSelection(ble, TransportSelectionReason.SPP_UNAVAILABLE)
        return when (facts.audioProfile) {
            AudioProfileEvidence.UNKNOWN ->
                TransportSelection(ble, TransportSelectionReason.AUDIO_PROFILE_UNKNOWN)
            AudioProfileEvidence.LE_AUDIO ->
                TransportSelection(ble, TransportSelectionReason.LE_AUDIO)
            AudioProfileEvidence.CLASSIC ->
                TransportSelection(TransportEndpoint.SppRfcomm(address, I7_CONTROL_SPP_UUID), TransportSelectionReason.CLASSIC_AUDIO)
        }
    }

    private data class ReadProgress(
        val keys: Set<String> = emptySet(),
        val meaningful: Boolean = false,
        val ended: Boolean = false,
        val keyRevisions: Map<String, Long> = emptyMap(),
        val fieldRevisions: Map<DeviceField, Long> = emptyMap(),
        val failure: Throwable? = null,
    )

    private class LinkEnded : Exception("设备连接已结束")

    private fun phase(attempt: Attempt, epoch: Long, phase: ConnectionPhase) = updateLinkAttempt(attempt, epoch) {
        val current = it.connection as? ConnectionState.Connecting
        if (current != null && phase != ConnectionPhase.READY && phase.ordinal >= current.phase.ordinal) {
            it.copy(connection = current.copy(phase = phase))
        } else it
    }

    private inline fun updateLinkAttempt(attempt: Attempt, epoch: Long, update: (ControlState) -> ControlState) {
        mutableState.update {
            if (attempt.linkEpoch.value == epoch && it.device?.sessionId == attempt.id && it.connection !is ConnectionState.Disconnected) update(it) else it
        }
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
        previous?.let { it.linkEpoch.value += 1 }
        mutableState.update { it.copy(connection = ConnectionState.Disconnected, device = null, transportSelection = null) }
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

    private companion object {
        const val I7_CONTROL_SPP_UUID = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
    }
}

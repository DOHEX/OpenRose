package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.ControlLink.Attempt
import io.github.dohex.openrose.core.ControlLink.LinkEnded
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Validation, ordered writes and fresh readback confirmation for the current device session. */
internal class SettingQueue(
    private val state: MutableStateFlow<ControlState>,
    private val scope: CoroutineScope,
    private val timing: SessionTiming,
) {
    private var operationId = 0L

    internal data class Request(val operation: SettingOperation, val result: CompletableDeferred<OperationResult>)

    internal class Pending {
        val commands = Channel<Request>(32)
        val requests = linkedMapOf<Long, Request>()
        var worker: Job? = null
    }

    fun start(link: ControlLink, attempt: Attempt) {
        attempt.settings.worker = scope.launch {
            for (request in attempt.settings.commands) execute(link, attempt, request)
        }
    }

    /** A caller owns its wait; the platform session owns the queued operation. */
    suspend fun set(link: ControlLink, setting: DeviceSetting, value: FieldValue, expectedSessionId: Long?): OperationResult {
        val request = link.lifecycle.withLock {
            val attempt = link.active?.takeIf { it.connection != null && !it.progress.value.ended }
            val current = state.value
            val session = if (setting == DeviceSetting.NOISE_MODE) current.noiseModeSession else current.readySession
            // Check the caller's reference under the same lock as admission.
            if (expectedSessionId != null && (attempt?.id != expectedSessionId || session?.sessionId != expectedSessionId)) {
                return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
            }
            link.platformError()?.let { return failure(it) }
            if (attempt == null || session?.sessionId != attempt.id) {
                return failure(ControlError(ControlErrorCode.NOT_READY, "设备尚未就绪"))
            }
            if (setting !in requireNotNull(state.value.device).capabilities.writableSettings) {
                return failure(ControlError(ControlErrorCode.UNSUPPORTED, "${setting.name} 尚不支持写入"))
            }
            // Validate before submission without allocating codec sequence numbers.
            validateSetting(requireNotNull(attempt.candidate.profile), setting, value)?.let { return failure(it) }
            if (attempt.settings.requests.size >= 32) return failure(ControlError(ControlErrorCode.NOT_READY, "设置队列已满"))
            val operation = SettingOperation(++operationId, attempt.id, setting, value, SettingStage.SUBMITTED)
            val next = Request(operation, CompletableDeferred())
            attempt.settings.requests[operation.id] = next
            state.update {
                it.copy(
                    settingOperations = it.settingOperations.mutate { operations ->
                        operations.add(operation)
                        if (operations.size > 64) operations.removeAt(0)
                    },
                    lastError = null,
                )
            }
            check(attempt.settings.commands.trySend(next).isSuccess)
            next
        }
        return request.result.await()
    }

    private fun validateSetting(profile: ModelProfile, setting: DeviceSetting, value: FieldValue): ControlError? {
        val device = requireNotNull(state.value.device)
        val capabilities = device.capabilities
        val noise = capabilities.noiseControl
        val invalid = ControlError(ControlErrorCode.INVALID_ARGUMENT, "${setting.name} 参数无效")
        fun checkValue(valid: Boolean) = invalid.takeUnless { valid }
        fun unsupported(message: String) = ControlError(ControlErrorCode.UNSUPPORTED, message)
        fun choice(choices: Set<String>, message: (String) -> String): ControlError? = when {
            value !is FieldValue.Text -> invalid
            value.value !in choices -> unsupported(message(value.value))
            else -> null
        }

        return device.settingPrerequisite(setting)?.let { ControlError(ControlErrorCode.NOT_READY, it) } ?: when (setting) {
            DeviceSetting.CUSTOM_EQ -> {
                val custom = capabilities.equalizer?.custom
                if (custom == null) {
                    unsupported("该型号不支持自定义均衡器")
                } else {
                    checkValue(
                        value is FieldValue.IntegerList && value.values.size == custom.bandsHz.size &&
                            value.values.all { it in custom.minGain..custom.maxGain },
                    )
                }
            }

            DeviceSetting.GAIN -> checkValue(value is FieldValue.Number && value.value in capabilities.gainLevels)

            DeviceSetting.HEARING_PROTECTION ->
                checkValue(value is FieldValue.Number && value.value in capabilities.hearingProtectionLevels)

            DeviceSetting.BASS_BOOST -> checkValue(value is FieldValue.Number && value.value in capabilities.bassBoostLevels)

            DeviceSetting.PROMPT_LANGUAGE -> checkValue(value is FieldValue.Text && value.value in capabilities.promptLanguages)

            DeviceSetting.PROMPT_VOLUME -> when {
                value !is FieldValue.Number -> invalid
                value.value in capabilities.promptVolumeLevels -> null
                value.value in profile.family.promptVolumeLevels -> unsupported(if (value.value == 0) "当前固件不支持关闭提示音" else "当前固件不支持此提示音档位")
                else -> invalid
            }

            DeviceSetting.TOUCH_BINDING -> validateTouchBinding(capabilities.touchControl, value)

            DeviceSetting.TAP_BINDING -> validateTouchBinding(capabilities.tapControl, value)

            DeviceSetting.GAME_MODE, DeviceSetting.MULTIPOINT, DeviceSetting.TAP_ENABLED, DeviceSetting.TOUCH_ENABLED,
            DeviceSetting.EAR_DETECTION, DeviceSetting.EAR_ADAPTIVE, DeviceSetting.SPATIAL_AUDIO,
            DeviceSetting.INDEPENDENT_MIC_ACTIVATION, DeviceSetting.INDEPENDENT_MIC_MODE,
            -> checkValue(value is FieldValue.Toggle)

            DeviceSetting.SPATIAL_MODE -> checkValue(value is FieldValue.Text && value.value in capabilities.spatialModes)

            DeviceSetting.SPATIAL_CONTENT -> checkValue(value is FieldValue.Text && value.value in capabilities.spatialContents)

            DeviceSetting.AUDIO_MODE -> choice(capabilities.audioControl?.modes.orEmpty()) { "该型号不支持 $it 音频配置" }

            DeviceSetting.EQ -> choice(capabilities.equalizer?.presets.orEmpty()) { "该型号与固件不支持 $it EQ" }

            DeviceSetting.NOISE_MODE -> choice(noise?.modes.orEmpty()) { "该型号不支持 $it 模式" }

            DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL -> {
                val level = noise?.levelCapabilities(setting)
                if (level == null) unsupported("该型号不支持此档位设置") else checkValue(value is FieldValue.Number && value.value in level.levels)
            }

            DeviceSetting.NOISE_CYCLE -> if (noise == null) {
                unsupported("该型号不支持降噪轮换")
            } else {
                checkValue(
                    value is FieldValue.NoiseCycle && value.flags.keys == noise.cycleModes &&
                        value.flags.values.all { it != null } && value.flags.values.count { it == true } >= noise.minimumCycleSelections,
                )
            }
        }
    }

    /** The initialization loop owns the wire until READY. Give a user command the next complete exchange. */
    suspend fun runNextDuringInitialization(link: ControlLink, attempt: Attempt) {
        val request = attempt.settings.commands.tryReceive().getOrNull() ?: return
        val prerequisite = if (state.value.noiseModeSession?.sessionId == attempt.id) null else "当前耳机尚不能调节降噪模式"
        execute(link, attempt, request, prerequisite)
    }

    private suspend fun execute(
        link: ControlLink,
        attempt: Attempt,
        request: Request,
        prerequisite: String? = state.value.device?.settingPrerequisite(request.operation.setting),
    ) {
        if (request.result.isCompleted) return
        val result = try {
            if (prerequisite == null) {
                applySetting(link, attempt, request)
            } else {
                settingFailure(attempt, request, ControlError(ControlErrorCode.NOT_READY, prerequisite), SettingStage.FAILED)
            }
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
            settingFailure(
                attempt,
                request,
                ControlError(code, cause.message ?: "设置失败"),
                if (code == ControlErrorCode.DISCONNECTED) SettingStage.DISCONNECTED else SettingStage.FAILED,
            )
        }
        link.lifecycle.withLock {
            attempt.settings.requests.remove(request.operation.id)
            request.result.complete(result)
            val error = (result as? OperationResult.Failure)?.error
            if (link.active === attempt && error?.code in setOf(ControlErrorCode.TIMEOUT, ControlErrorCode.FAILED)) {
                // Neither protocol correlates a late readback with a transaction. End this
                // link before another setting can consume an old response or native write.
                link.clearActive()
                state.update { it.copy(lastError = error) }
            }
        }
    }

    private suspend fun applySetting(owner: ControlLink, attempt: Attempt, request: Request): OperationResult {
        val link = attempt.connection ?: throw LinkEnded()
        val operation = request.operation
        val device = state.value.device ?: throw LinkEnded()
        val firmware = (device.fields[DeviceField.FIRMWARE] as? FieldReading.Known)?.value as? FieldValue.Firmware
        val prepared = attempt.codec.prepareSetting(operation.setting, operation.requestedValue, firmware, device.fields)
        settingStage(attempt, request, SettingStage.SUBMITTED, totalFrames = prepared.writeFrames.size)
        if ((prepared.writeFrames + prepared.readbacks.map { it.bytes }).any { it.size > link.maxWriteSize }) {
            throw UnsupportedOperationException("完整协议设置或回读超出传输写入容量")
        }
        for ((index, frame) in prepared.writeFrames.withIndex()) {
            owner.writePaced(attempt, link, frame)
            settingStage(
                attempt,
                request,
                if (index == prepared.writeFrames.lastIndex) SettingStage.WRITTEN else SettingStage.PARTIALLY_WRITTEN,
                writtenFrames = index + 1,
            )
        }
        val fieldBaselines = mutableMapOf<DeviceField, Long>()
        var actualValues = emptyMap<DeviceField, FieldValue>()
        for (query in prepared.readbacks) {
            // A later field query gets its own baseline; earlier unsolicited data cannot confirm it.
            val baseline = owner.writePaced(attempt, link, query.bytes)
            for (field in query.expectedFields) {
                if (field in prepared.expectedValues) fieldBaselines[field] = baseline.fieldRevisions[field] ?: 0L
            }
            val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                attempt.progress.first { progress ->
                    if (progress.ended) return@first true
                    // A refresh with no requested value still owns its reply before the next write can run.
                    val received = query.responseKeys.all { (progress.keyRevisions[it] ?: 0) > (baseline.keyRevisions[it] ?: 0) }
                    val fields = state.value.device?.fields.orEmpty()
                    actualValues = fieldBaselines.mapNotNull { (field, revision) ->
                        if (!received && field in query.expectedFields) return@mapNotNull null
                        if ((progress.fieldRevisions[field] ?: 0) <= revision) return@mapNotNull null
                        confirmationValue(fields[field], prepared.expectedValues.getValue(field))?.let { field to it }
                    }.toMap()
                    val confirmed = actualValues.filter { (field, value) -> value == prepared.expectedValues[field] }.keys
                    settingStage(attempt, request, SettingStage.WRITTEN, confirmedFields = confirmed)
                    received && actualValues.size == fieldBaselines.size
                }
            }
            if (observed?.ended == true) throw LinkEnded()
            owner.ensureActive(attempt)
            if (observed == null) {
                return settingFailure(
                    attempt,
                    request,
                    ControlError(ControlErrorCode.TIMEOUT, "未收到有效的设置回读"),
                    SettingStage.TIMED_OUT,
                )
            }
        }
        return if (prepared.expectedValues.all { (field, expected) -> actualValues[field] == expected }) {
            settingStage(attempt, request, SettingStage.CONFIRMED)
            OperationResult.Success
        } else {
            settingFailure(attempt, request, ControlError(ControlErrorCode.NOT_APPLIED, "设备回读与请求不一致"), SettingStage.NOT_APPLIED)
        }
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

    private fun settingStage(
        attempt: Attempt,
        request: Request,
        stage: SettingStage,
        error: ControlError? = null,
        writtenFrames: Int? = null,
        totalFrames: Int? = null,
        confirmedFields: Set<DeviceField>? = null,
    ) {
        state.update { current ->
            if (current.device?.sessionId != attempt.id || request.result.isCompleted) {
                current
            } else {
                current.copy(
                    settingOperations = current.settingOperations.mutate { operations ->
                        val index = operations.indexOfFirst { it.id == request.operation.id }
                        if (index >= 0) {
                            val operation = operations[index]
                            operations[index] = operation.copy(
                                stage = stage,
                                error = error,
                                writtenFrames = writtenFrames ?: operation.writtenFrames,
                                totalFrames = totalFrames ?: operation.totalFrames,
                                confirmedFields = confirmedFields?.toPersistentSet() ?: operation.confirmedFields,
                            )
                        }
                    },
                    lastError = error,
                )
            }
        }
    }

    private fun settingFailure(attempt: Attempt, request: Request, error: ControlError, stage: SettingStage): OperationResult.Failure {
        settingStage(attempt, request, stage, error)
        return OperationResult.Failure(error)
    }

    /** Caller holds lifecycle; complete every waiter before cancelling the owned worker. */
    fun end(attempt: Attempt) {
        val error = ControlError(ControlErrorCode.DISCONNECTED, "连接会话已结束")
        val ids = attempt.settings.requests.keys.toSet()
        state.update { current ->
            current.copy(
                settingOperations = current.settingOperations.mutate { operations ->
                    for (index in operations.indices) {
                        val operation = operations[index]
                        if (operation.id in ids) operations[index] = operation.copy(stage = SettingStage.DISCONNECTED, error = error)
                    }
                },
            )
        }
        attempt.settings.requests.values.forEach { it.result.complete(OperationResult.Failure(error)) }
        attempt.settings.requests.clear()
        attempt.settings.commands.close()
        attempt.settings.worker?.cancel()
    }

    private fun failure(error: ControlError): OperationResult.Failure {
        state.update { it.copy(lastError = error) }
        return OperationResult.Failure(error)
    }
}

private fun validateTouchBinding(touch: TouchControlCapabilities?, value: FieldValue): ControlError? {
    if (value !is FieldValue.TouchAssignment) return ControlError(ControlErrorCode.INVALID_ARGUMENT, "触控绑定需要作用耳、手势和动作")
    if (touch == null) return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持触控绑定")
    if (TouchSlot(value.side, value.gesture) !in touch.slots) {
        return ControlError(ControlErrorCode.INVALID_ARGUMENT, "触控作用耳或手势无效")
    }
    if (value.action !in touch.actions) return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持 ${value.action} 触控动作")
    return null
}

package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.ControlLink.Attempt
import io.github.dohex.openrose.core.ControlLink.LinkEnded
import io.github.dohex.openrose.core.ControlLink.ReadProgress
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolQuery
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
import kotlinx.coroutines.withTimeout
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
        attempt.settings.worker = scope.launch { runSettings(link, attempt) }
    }

    /** A caller owns its wait; the platform session owns the queued operation. */
    suspend fun set(link: ControlLink, setting: DeviceSetting, value: FieldValue, expectedSessionId: Long?): OperationResult {
        val request = link.lifecycle.withLock {
            val attempt = link.active
            if (expectedSessionId != null) {
                // Checked under the same lock as admission, so a session replaced meanwhile never receives the write.
                val connection = state.value.connection as? ConnectionState.Connected
                if (attempt == null || attempt.id != expectedSessionId || connection?.sessionId != expectedSessionId) {
                    return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
                }
            }
            link.platformError()?.let { return failure(it) }
            if (attempt == null || state.value.connection !is ConnectionState.Connected) {
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

            DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL -> {
                val level = noise.levelCapabilities(setting)
                    ?: return ControlError(ControlErrorCode.UNSUPPORTED, "该型号不支持此档位设置")
                // The original sender has no mode precondition; visibility is a separate rule.
                value is FieldValue.Number && value.value in level.levels
            }

            DeviceSetting.NOISE_CYCLE ->
                value is FieldValue.NoiseCycle && value.flags.keys == noise.cycleModes &&
                    value.flags.values.all { it != null } && value.flags.values.count { it == true } >= noise.minimumCycleSelections
        }
        return if (valid) null else ControlError(ControlErrorCode.INVALID_ARGUMENT, "${setting.name} 参数无效")
    }

    private suspend fun runSettings(link: ControlLink, attempt: Attempt) {
        for (request in attempt.settings.commands) {
            if (request.result.isCompleted) continue
            val result = try {
                applySetting(link, attempt, request)
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
    }

    private suspend fun applySetting(owner: ControlLink, attempt: Attempt, request: Request): OperationResult {
        val link = attempt.connection ?: throw LinkEnded()
        val operation = request.operation
        val prepared = attempt.codec.prepareSetting(operation.setting, operation.requestedValue, firmware())
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
        val baselines = mutableListOf<Pair<ProtocolQuery, ReadProgress>>()
        var actualValues = emptyMap<DeviceField, FieldValue>()
        for (query in prepared.readbacks) {
            // A later field query gets its own baseline; earlier unsolicited data cannot confirm it.
            owner.pace(attempt)
            owner.ensureActive(attempt)
            baselines += query to attempt.progress.value
            withTimeout(timing.queryTimeoutMillis) { link.write(query.bytes.copyOf()) }
            val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                attempt.progress.first { progress ->
                    if (progress.ended) {
                        true
                    } else {
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
                        } else {
                            false
                        }
                    }
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

    private fun firmware(): FieldValue.Firmware? = (state.value.device?.fields?.get(DeviceField.FIRMWARE) as? FieldReading.Known)?.value as? FieldValue.Firmware

    private fun failure(error: ControlError): OperationResult.Failure {
        state.update { it.copy(lastError = error) }
        return OperationResult.Failure(error)
    }
}

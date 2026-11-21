package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.ControlLink.Attempt
import io.github.dohex.openrose.core.ControlLink.LinkEnded
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
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

    internal data class Request(
        val command: HeadsetCommand,
        val operation: SettingOperation,
        val result: CompletableDeferred<OperationResult>,
    )

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
    suspend fun set(link: ControlLink, command: HeadsetCommand, expectedSessionId: Long?): OperationResult {
        val request = link.lifecycle.withLock {
            val attempt = link.active?.takeIf { it.connection != null && !it.progress.value.ended }
            val current = state.value
            val session = current.sessionFor(command.setting)
            // Check the caller's reference under the same lock as admission.
            if (expectedSessionId != null && (attempt?.id != expectedSessionId || session?.sessionId != expectedSessionId)) {
                return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
            }
            link.platformError()?.let { return failure(it) }
            if (attempt == null || session?.sessionId != attempt.id) {
                return failure(ControlError(ControlErrorCode.NOT_READY, "设备尚未就绪"))
            }
            // Validate before submission without allocating codec sequence numbers.
            requireNotNull(current.device).validate(command)?.let { return failure(it) }
            if (attempt.settings.requests.size >= 32) return failure(ControlError(ControlErrorCode.NOT_READY, "设置队列已满"))
            val operation = SettingOperation(++operationId, attempt.id, command.setting, command.value, SettingStage.SUBMITTED)
            val next = Request(command, operation, CompletableDeferred())
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
        prerequisite: String? = null,
    ) {
        if (request.result.isCompleted) return
        val result = try {
            validateAndApply(link, attempt, request, prerequisite)
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

    /** A queued write belongs to its original session and must still be valid for the latest readings. */
    private suspend fun validateAndApply(
        link: ControlLink,
        attempt: Attempt,
        request: Request,
        prerequisite: String?,
    ): OperationResult {
        val device = state.value.device?.takeIf { it.sessionId == attempt.id } ?: throw LinkEnded()
        val error = prerequisite?.let { ControlError(ControlErrorCode.NOT_READY, it) } ?: device.validate(request.command)
        return if (error == null) {
            applySetting(link, attempt, request, device)
        } else {
            settingFailure(attempt, request, error, SettingStage.FAILED)
        }
    }

    private suspend fun applySetting(owner: ControlLink, attempt: Attempt, request: Request, device: DeviceState): OperationResult {
        val link = attempt.connection ?: throw LinkEnded()
        val operation = request.operation
        val firmware = device.knownValue(DeviceField.FIRMWARE) as? FieldValue.Firmware
        val prepared = attempt.codec.prepareSetting(operation.setting, operation.requestedValue, firmware, device.diagnostics.fields)
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
                    val fields = state.value.device?.diagnostics?.fields.orEmpty()
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

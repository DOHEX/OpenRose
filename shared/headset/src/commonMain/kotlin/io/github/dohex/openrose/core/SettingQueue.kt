package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ProtocolSetting
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.transportOperation
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.toPersistentSet
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

    fun start(link: ControlLink, attempt: ConnectionAttempt) {
        attempt.settings.worker = scope.launch {
            for (request in attempt.settings.commands) execute(link, attempt, request)
        }
    }

    suspend fun set(link: ControlLink, command: HeadsetCommand, expectedSessionId: Long?): OperationResult {
        val request = link.lifecycle.withLock {
            val attempt = link.active?.takeIf { it.connection != null && !it.progress.value.ended }
            val current = state.value
            val session = current.sessionFor(command.setting)
            if (expectedSessionId != null && (attempt?.id != expectedSessionId || session?.sessionId != expectedSessionId)) {
                return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
            }
            link.platformError()?.let { return failure(it) }
            if (attempt == null || session?.sessionId != attempt.id) {
                return failure(ControlError(ControlErrorCode.NOT_READY, "设备尚未就绪"))
            }
            requireNotNull(current.device).validate(command)?.let { return failure(it) }
            if (attempt.settings.requests.size >= 32) return failure(ControlError(ControlErrorCode.NOT_READY, "设置队列已满"))
            val operation = SettingOperation(++operationId, attempt.id, command.setting, command.value, SettingStage.SUBMITTED)
            val next = Request(command, operation, CompletableDeferred())
            attempt.settings.requests[operation.id] = next
            state.update { it.withSettingOperation(operation).copy(lastError = null) }
            check(attempt.settings.commands.trySend(next).isSuccess)
            next
        }
        return request.result.await()
    }

    suspend fun runNextDuringInitialization(link: ControlLink, attempt: ConnectionAttempt) {
        val request = attempt.settings.commands.tryReceive().getOrNull() ?: return
        execute(link, attempt, request)
    }

    private suspend fun execute(link: ControlLink, attempt: ConnectionAttempt, request: Request) {
        if (request.result.isCompleted) return
        val result = try {
            transportOperation(onFailure = { cause ->
                val error = commandFailure(cause)
                val stage = if (error.code == ControlErrorCode.DISCONNECTED) SettingStage.DISCONNECTED else SettingStage.FAILED
                settingFailure(attempt, request, error, stage)
            }) {
                validateAndApply(link, attempt, request)
            }
        } catch (_: TimeoutCancellationException) {
            settingFailure(attempt, request, ControlError(ControlErrorCode.TIMEOUT, "设置或设备回读超时"), SettingStage.TIMED_OUT)
        }
        link.lifecycle.withLock {
            attempt.settings.requests.remove(request.operation.id)
            request.result.complete(result)
            val error = (result as? OperationResult.Failure)?.error
            if (link.active === attempt && error?.code in setOf(ControlErrorCode.TIMEOUT, ControlErrorCode.FAILED)) {
                link.clearActive()
                state.update { it.copy(lastError = error) }
            }
        }
    }

    private suspend fun validateAndApply(link: ControlLink, attempt: ConnectionAttempt, request: Request): OperationResult {
        val current = state.value
        val device = current.device?.takeIf { it.sessionId == attempt.id } ?: throw LinkEnded()
        val error = if (current.sessionFor(request.command.setting)?.sessionId == attempt.id) {
            device.validate(request.command)
        } else {
            ControlError(ControlErrorCode.NOT_READY, "设备尚未就绪")
        }
        return if (error == null) {
            applySetting(link, attempt, request, device)
        } else {
            settingFailure(attempt, request, error, SettingStage.FAILED)
        }
    }

    private suspend fun applySetting(
        owner: ControlLink,
        attempt: ConnectionAttempt,
        request: Request,
        device: DeviceState,
    ): OperationResult {
        val link = attempt.connection ?: throw LinkEnded()
        val prepared = attempt.codec.prepareSetting(request.command, device)
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
        return confirmReadbacks(owner, attempt, request, link, prepared)
    }

    private suspend fun confirmReadbacks(
        owner: ControlLink,
        attempt: ConnectionAttempt,
        request: Request,
        link: ControlConnection,
        prepared: ProtocolSetting,
    ): OperationResult {
        val confirmation = SettingConfirmation(prepared.expectedValues)
        for (query in prepared.readbacks) {
            val baseline = owner.writePaced(attempt, link, query.bytes)
            confirmation.beginQuery(query, baseline)
            val observed = withTimeoutOrNull(timing.queryTimeoutMillis) {
                attempt.progress.first { progress ->
                    if (progress.ended) return@first true
                    val fields = state.value.device?.diagnostics?.fields.orEmpty()
                    val finished = confirmation.observe(query, baseline, progress, fields)
                    settingStage(attempt, request, SettingStage.WRITTEN, confirmedFields = confirmation.confirmedFields)
                    finished
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
        return if (confirmation.applied) {
            settingStage(attempt, request, SettingStage.CONFIRMED)
            OperationResult.Success
        } else {
            settingFailure(attempt, request, ControlError(ControlErrorCode.NOT_APPLIED, "设备回读与请求不一致"), SettingStage.NOT_APPLIED)
        }
    }

    private fun settingStage(
        attempt: ConnectionAttempt,
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

    private fun settingFailure(
        attempt: ConnectionAttempt,
        request: Request,
        error: ControlError,
        stage: SettingStage,
    ): OperationResult.Failure {
        settingStage(attempt, request, stage, error)
        return OperationResult.Failure(error)
    }

    fun end(attempt: ConnectionAttempt) {
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

private fun commandFailure(cause: Exception): ControlError {
    val code = when (cause) {
        is LinkEnded -> ControlErrorCode.DISCONNECTED
        is UnsupportedOperationException -> ControlErrorCode.UNSUPPORTED
        is IllegalArgumentException -> ControlErrorCode.INVALID_ARGUMENT
        else -> ControlErrorCode.FAILED
    }
    return ControlError(code, cause.message ?: "设置失败")
}

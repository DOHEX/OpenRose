package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ProtocolSetting
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import io.github.dohex.openrose.core.validate
import io.github.dohex.openrose.core.withReadings
import io.github.dohex.openrose.core.withSettingOperation
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

private const val WRITE_MILLIS = 250L
private const val READBACK_MILLIS = 600L

internal class SandboxSession(val id: Long) {
    val writing = Mutex()
}

/**
 * Setting writes as the control session records them: submitted, written, then ended the way the scenario says.
 * Each write is prepared by the model's real codec, so a value the codec rejects fails here as it would on a device,
 * and a confirmed write leaves exactly the readings the codec expects back.
 */
internal class SandboxWrites(
    private val state: MutableStateFlow<ControlState>,
    private val scenario: StateFlow<SandboxScenario>,
    private val scope: CoroutineScope,
) {
    private val operationIds = AtomicLong()
    private val sessionEnded = ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换")

    /** Record admission before returning. The sandbox owns execution even if a caller stops waiting. */
    fun submit(
        session: SandboxSession,
        expectedSessionId: Long,
        command: HeadsetCommand,
    ): Deferred<OperationResult> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        val current = state.value
        if (session.id != expectedSessionId || current.sessionFor(command.setting)?.sessionId != session.id) {
            return@async OperationResult.Failure(sessionEnded)
        }
        val error = requireNotNull(current.device).validate(command)
        if (error != null) {
            state.update { if (it.device?.sessionId == session.id) it.copy(lastError = error) else it }
            return@async OperationResult.Failure(error)
        }
        val operation = SettingOperation(operationIds.incrementAndGet(), session.id, command.setting, command.value, SettingStage.SUBMITTED)
        record(operation)
        session.writing.withLock { execute(operation, command) }
    }

    private suspend fun execute(operation: SettingOperation, command: HeadsetCommand): OperationResult {
        val current = state.value
        if (current.sessionFor(command.setting)?.sessionId != operation.sessionId) {
            return record(operation.copy(stage = SettingStage.DISCONNECTED, error = sessionEnded))
        }
        val device = requireNotNull(current.device)
        device.validate(command)?.let { return record(operation.copy(stage = SettingStage.FAILED, error = it)) }

        val plan = runCatching {
            createProtocolCodec(device.profile).prepareSetting(command, device)
        }
        return plan.fold(
            onSuccess = { simulateWrite(operation, it) },
            onFailure = { rejection ->
                val code = if (rejection is IllegalArgumentException) ControlErrorCode.INVALID_ARGUMENT else ControlErrorCode.UNSUPPORTED
                val error = ControlError(code, rejection.message ?: rejection.toString())
                record(operation.copy(stage = SettingStage.FAILED, error = error))
            },
        )
    }

    private suspend fun simulateWrite(operation: SettingOperation, plan: ProtocolSetting): OperationResult {
        val submitted = operation.copy(totalFrames = plan.writeFrames.size)
        record(submitted)
        delay(WRITE_MILLIS)
        val written = submitted.copy(stage = SettingStage.WRITTEN, writtenFrames = submitted.totalFrames)
        val writeResult = record(written)
        if (writeResult is OperationResult.Failure) return writeResult

        delay(READBACK_MILLIS)
        return when (scenario.value.writeOutcome) {
            SandboxWriteOutcome.CONFIRMED -> record(
                written.copy(stage = SettingStage.CONFIRMED, confirmedFields = plan.expectedValues.keys.toPersistentSet()),
                plan.expectedValues,
            )

            SandboxWriteOutcome.TIMED_OUT -> record(
                written.copy(stage = SettingStage.TIMED_OUT, error = ControlError(ControlErrorCode.TIMEOUT, "没有收到有效的设置回读")),
            )

            SandboxWriteOutcome.NOT_APPLIED -> record(
                written.copy(stage = SettingStage.NOT_APPLIED, error = ControlError(ControlErrorCode.NOT_APPLIED, "设备状态与请求不一致")),
            )
        }
    }

    /** A late result updates the original operation's history only. */
    private fun record(operation: SettingOperation, expected: Map<DeviceField, FieldValue> = emptyMap()): OperationResult {
        val updated = state.updateAndGet { current ->
            if (current.sessionFor(operation.setting)?.sessionId != operation.sessionId) {
                val previous = current.settingOperations.firstOrNull { it.id == operation.id } ?: operation
                current.withSettingOperation(previous.copy(stage = SettingStage.DISCONNECTED, error = sessionEnded))
            } else {
                val device = requireNotNull(current.device)
                val confirmed = if (expected.isEmpty()) device else device.withReadings(device.simulateReadback(expected))
                current.withSettingOperation(operation).copy(device = confirmed, lastError = operation.error)
            }
        }
        val error = updated.settingOperations.first { it.id == operation.id }.error
        return error?.let(OperationResult::Failure) ?: OperationResult.Success
    }
}

/**
 * The fields a confirmed write leaves behind: the codec's expected readback, completed the way the device's own
 * reply completes it. A touch assignment lands in its slot of the full binding table, and a bass level reads back
 * together with the custom-slot EQ mode from the same reply.
 */
private fun DeviceState.simulateReadback(
    expected: Map<DeviceField, FieldValue>,
): Map<DeviceField, FieldReading> = expected.mapValues { (field, value) ->
    val reading = if (value is FieldValue.TouchAssignment) {
        val current = knownValue(field) as? FieldValue.TouchKeys
        FieldValue.TouchKeys(current?.bindings.orEmpty().assigning(value))
    } else {
        value
    }
    FieldReading.Known(reading, "")
}

/** The binding table with [assignment] in its slot; the device keeps the slot's own raw action code. */
private fun List<TouchBinding>.assigning(assignment: FieldValue.TouchAssignment): List<TouchBinding> {
    val assigned = TouchBinding(assignment.side, assignment.gesture, assignment.action, -1)
    val inSlot = { binding: TouchBinding -> binding.side == assignment.side && binding.gesture == assignment.gesture }
    return if (any(inSlot)) map { if (inSlot(it)) assigned.copy(rawAction = it.rawAction) else it } else this + assigned
}

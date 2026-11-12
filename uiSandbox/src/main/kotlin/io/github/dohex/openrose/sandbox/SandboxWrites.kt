package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val WRITE_MILLIS = 250L
private const val READBACK_MILLIS = 600L

/** The control session keeps this many recent operations, terminal ones included. */
private const val OPERATION_HISTORY = 64

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
    private var lastOperationId = 0L

    fun submit(setting: DeviceSetting, value: FieldValue) {
        val run = begin(setting, value, null) ?: return
        scope.launch { run() }
    }

    /** Like [submit], but only to [sessionId], and returns once the scenario ends the write. */
    suspend fun write(sessionId: Long, setting: DeviceSetting, value: FieldValue): OperationResult {
        val run = begin(setting, value, sessionId)
            ?: return OperationResult.Failure(ControlError(ControlErrorCode.DISCONNECTED, "请求的设备会话已结束或已被替换"))
        return run()
    }

    /** Records the submission now; the returned step plays out the rest, or null when no session can take the write. */
    private fun begin(setting: DeviceSetting, value: FieldValue, sessionId: Long?): (suspend () -> OperationResult)? {
        val current = state.value
        val session = current.readySession ?: return null
        if (sessionId != null && session.sessionId != sessionId) return null
        val device = current.device ?: return null
        val prepared = runCatching { createProtocolCodec(device.profile).prepareSetting(setting, value, scenario.value.firmwareValue) }
        val operation = SettingOperation(
            id = ++lastOperationId,
            sessionId = device.sessionId,
            setting = setting,
            requestedValue = value,
            stage = SettingStage.SUBMITTED,
            totalFrames = prepared.getOrNull()?.writeFrames?.size ?: 0,
        )
        record(operation)
        val plan = prepared.getOrElse { rejection ->
            val code = if (rejection is IllegalArgumentException) ControlErrorCode.INVALID_ARGUMENT else ControlErrorCode.UNSUPPORTED
            val error = ControlError(code, rejection.message ?: rejection.toString())
            finish(operation, SettingStage.FAILED, error)
            return { OperationResult.Failure(error) }
        }
        val written = operation.copy(stage = SettingStage.WRITTEN, writtenFrames = plan.writeFrames.size)
        return {
            delay(WRITE_MILLIS)
            record(written)
            delay(READBACK_MILLIS)
            when (scenario.value.writeOutcome) {
                SandboxWriteOutcome.CONFIRMED -> confirm(written, plan.expectedValues)

                SandboxWriteOutcome.TIMED_OUT ->
                    finish(written, SettingStage.TIMED_OUT, ControlError(ControlErrorCode.TIMEOUT, "没有收到有效的设置回读"))

                SandboxWriteOutcome.NOT_APPLIED ->
                    finish(written, SettingStage.NOT_APPLIED, ControlError(ControlErrorCode.NOT_APPLIED, "设备状态与请求不一致"))
            }
        }
    }

    /** A readback for a session that has since ended cannot confirm anything. */
    private fun confirm(operation: SettingOperation, expected: Map<DeviceField, FieldValue>): OperationResult {
        var result: OperationResult = OperationResult.Success
        state.update { current ->
            val device = current.device?.takeIf { it.sessionId == operation.sessionId }
            if (device == null) {
                val error = ControlError(ControlErrorCode.DISCONNECTED, "控制连接已断开，设置未获完整确认")
                result = OperationResult.Failure(error)
                current.withOperation(operation.copy(stage = SettingStage.DISCONNECTED, error = error)).copy(lastError = error)
            } else {
                result = OperationResult.Success
                current.withOperation(operation.copy(stage = SettingStage.CONFIRMED, confirmedFields = expected.keys.toPersistentSet()))
                    .copy(device = device.copy(fields = confirmedFields(device.fields, expected)))
            }
        }
        return result
    }

    private fun finish(operation: SettingOperation, stage: SettingStage, error: ControlError): OperationResult {
        state.update { it.withOperation(operation.copy(stage = stage, error = error)).copy(lastError = error) }
        return OperationResult.Failure(error)
    }

    private fun record(operation: SettingOperation) = state.update { it.withOperation(operation) }
}

private fun ControlState.withOperation(operation: SettingOperation): ControlState {
    val replaced = settingOperations.indexOfFirst { it.id == operation.id }
    val operations = if (replaced >= 0) {
        settingOperations.replacingAt(replaced, operation)
    } else {
        settingOperations.adding(operation)
    }
    return copy(settingOperations = operations.takeLast(OPERATION_HISTORY).toPersistentList())
}

/**
 * The fields a confirmed write leaves behind: the codec's expected readback, completed the way the device's own
 * reply completes it. A touch assignment lands in its slot of the full binding table, and a bass level reads back
 * together with the custom-slot EQ mode from the same reply.
 */
private fun confirmedFields(
    fields: PersistentMap<DeviceField, FieldReading>,
    expected: Map<DeviceField, FieldValue>,
): PersistentMap<DeviceField, FieldReading> {
    val result = fields.builder()
    for ((field, value) in expected) {
        when {
            value is FieldValue.TouchAssignment -> {
                val current = (fields[DeviceField.TOUCH_KEYS] as? FieldReading.Known)?.value as? FieldValue.TouchKeys
                result[DeviceField.TOUCH_KEYS] = FieldReading.Known(FieldValue.TouchKeys(current?.bindings.orEmpty().assigning(value)), "")
            }

            field == DeviceField.EQ_SLOT && value is FieldValue.Number -> {
                val reply = "05%02x".format(value.value)
                result[DeviceField.EQ] = FieldReading.Known(FieldValue.Text("custom_slot"), reply)
                result[DeviceField.EQ_SLOT] = FieldReading.Known(value, reply)
            }

            else -> result[field] = FieldReading.Known(value, "")
        }
    }
    return result.build()
}

/** The binding table with [assignment] in its slot; the device keeps the slot's own raw action code. */
private fun List<TouchBinding>.assigning(assignment: FieldValue.TouchAssignment): List<TouchBinding> {
    val assigned = TouchBinding(assignment.side, assignment.gesture, assignment.action, -1)
    val inSlot = { binding: TouchBinding -> binding.side == assignment.side && binding.gesture == assignment.gesture }
    return if (any(inSlot)) map { if (inSlot(it)) assigned.copy(rawAction = it.rawAction) else it } else this + assigned
}

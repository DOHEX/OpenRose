package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.CoreDecodeResult
import io.github.dohex.openrose.ffi.decodeControlCommand
import io.github.dohex.openrose.ffi.encodeToBytes
import io.github.dohex.openrose.ffi.kotlincore.toKotlin
import io.github.dohex.openrose.ffi.kotlincore.toRust
import uniffi.openrose_control.ControlCommand
import uniffi.openrose_control.SessionReference as RustSessionReference

object HostWire {
    fun encode(command: HostCommand): ByteArray = command.toRust().encodeToBytes()

    fun decodeCommand(bytes: ByteArray): CoreDecodeResult<HostCommand> = when (val result = decodeControlCommand(bytes)) {
        is CoreDecodeResult.Ok -> CoreDecodeResult.Ok(result.value.toKotlin())
        is CoreDecodeResult.Error -> result
    }

    fun encode(result: HostCallResult): Wire = when (result) {
        is HostCallResult.Operation -> obj("t" to str("operation"), "result" to ControlWire.encode(result.result))

        is HostCallResult.Flag -> obj("t" to str("flag"), "value" to bool(result.value))

        is HostCallResult.Rejected -> obj(
            "t" to str("rejected"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
        )
    }

    fun decodeCallResult(wire: Wire): HostCallResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "operation" -> HostCallResult.Operation(ControlWire.decodeResult(node.field("result")))
            "flag" -> HostCallResult.Flag(node.bool("value"))
            "rejected" -> HostCallResult.Rejected(node.field("rejection").asEnum<HostRejection>(), node.str("message"))
            else -> throw WireFormatException("未知调用结果 $kind")
        }
    }
}

private fun HostCommand.toRust(): ControlCommand = when (this) {
    HostCommand.RefreshBondedDevices -> ControlCommand.RefreshBondedHeadsets
    HostCommand.StartScan -> ControlCommand.StartScan
    HostCommand.StopScan -> ControlCommand.StopScan
    is HostCommand.SelectCurrentHeadset -> ControlCommand.SelectCurrentHeadset(deviceId.value)
    is HostCommand.Connect -> ControlCommand.Connect(deviceId.value)
    is HostCommand.ConnectFromSystem -> ControlCommand.ConnectPinned(deviceId.value)
    is HostCommand.ConnectForAddDevice -> ControlCommand.ConnectForAddHeadset(deviceId.value)
    HostCommand.Disconnect -> ControlCommand.Disconnect
    is HostCommand.Set -> ControlCommand.Set(reference?.toRust(), command.toRust())
    is HostCommand.ClaimPrompt -> ControlCommand.ClaimPrompt(reference.toRust(), promptId)
}

private fun ControlCommand.toKotlin(): HostCommand = when (this) {
    ControlCommand.RefreshBondedHeadsets -> HostCommand.RefreshBondedDevices
    ControlCommand.StartScan -> HostCommand.StartScan
    ControlCommand.StopScan -> HostCommand.StopScan
    is ControlCommand.SelectCurrentHeadset -> HostCommand.SelectCurrentHeadset(DeviceId(headsetId))
    is ControlCommand.Connect -> HostCommand.Connect(DeviceId(headsetId))
    is ControlCommand.ConnectPinned -> HostCommand.ConnectFromSystem(DeviceId(headsetId))
    is ControlCommand.ConnectForAddHeadset -> HostCommand.ConnectForAddDevice(DeviceId(headsetId))
    ControlCommand.Disconnect -> HostCommand.Disconnect
    is ControlCommand.Set -> HostCommand.Set(reference?.toKotlin(), command.toKotlin())
    is ControlCommand.ClaimPrompt -> HostCommand.ClaimPrompt(reference.toKotlin(), promptId)
}

private fun SessionReference.toRust(): RustSessionReference = RustSessionReference(deviceId.value, sessionId.toULong())

private fun RustSessionReference.toKotlin(): SessionReference = SessionReference(DeviceId(headsetId), sessionId.toLong())

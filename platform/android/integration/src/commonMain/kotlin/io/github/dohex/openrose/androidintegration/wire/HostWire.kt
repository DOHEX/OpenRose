package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.transport.DeviceId

object HostWire {
    fun encode(command: HostCommand): Wire = when (command) {
        HostCommand.RefreshBondedDevices -> obj("t" to str("refresh"))

        HostCommand.StartScan -> obj("t" to str("startScan"))

        HostCommand.StopScan -> obj("t" to str("stopScan"))

        is HostCommand.Connect -> obj("t" to str("connect"), "device" to str(command.deviceId.value))

        is HostCommand.ConnectFromSystem -> obj("t" to str("connectSystem"), "device" to str(command.deviceId.value))

        is HostCommand.ConnectForAddDevice -> obj("t" to str("connectAdd"), "device" to str(command.deviceId.value))

        HostCommand.Disconnect -> obj("t" to str("disconnect"))

        is HostCommand.Set -> obj(
            "t" to str("set"),
            "reference" to command.reference?.let(ControlWire::encode),
            "setting" to Wire.Str(command.command.setting.name),
            "value" to ControlWire.encode(command.command.value),
        )

        is HostCommand.ClaimPrompt -> obj(
            "t" to str("claim"),
            "reference" to ControlWire.encode(command.reference),
            "prompt" to str(command.promptId),
        )
    }

    fun decodeCommand(wire: Wire): HostCommand {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "refresh" -> HostCommand.RefreshBondedDevices

            "startScan" -> HostCommand.StartScan

            "stopScan" -> HostCommand.StopScan

            "connect" -> HostCommand.Connect(DeviceId(node.str("device")))

            "connectSystem" -> HostCommand.ConnectFromSystem(DeviceId(node.str("device")))

            "connectAdd" -> HostCommand.ConnectForAddDevice(DeviceId(node.str("device")))

            "disconnect" -> HostCommand.Disconnect

            "set" -> HostCommand.Set(
                node.optional("reference")?.let(ControlWire::decodeReference),
                HeadsetCommand.decode(node.field("setting").asEnum<DeviceSetting>(), ControlWire.decodeValue(node.field("value")))
                    ?: throw WireFormatException("耳机设置与参数类型不符"),
            )

            "claim" -> HostCommand.ClaimPrompt(ControlWire.decodeReference(node.field("reference")), node.str("prompt"))

            else -> throw WireFormatException("未知命令 $kind")
        }
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

    fun encodeCall(generation: OwnerGeneration, command: HostCommand): Wire = obj(
        "generation" to HostIdentityWire.encode(generation),
        "command" to encode(command),
    )

    fun decodeCall(wire: Wire): Pair<OwnerGeneration, HostCommand> = wire.asObj()
        .let { HostIdentityWire.decodeGeneration(it.field("generation")) to decodeCommand(it.field("command")) }
}

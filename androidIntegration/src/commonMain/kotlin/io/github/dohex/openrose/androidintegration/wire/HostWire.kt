package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.AcquireResult
import io.github.dohex.openrose.androidintegration.BridgeIpcState
import io.github.dohex.openrose.androidintegration.ClientRole
import io.github.dohex.openrose.androidintegration.ControlWay
import io.github.dohex.openrose.androidintegration.HostCallResult
import io.github.dohex.openrose.androidintegration.HostCommand
import io.github.dohex.openrose.androidintegration.HostFacts
import io.github.dohex.openrose.androidintegration.HostFrame
import io.github.dohex.openrose.androidintegration.HostLease
import io.github.dohex.openrose.androidintegration.HostOpenResult
import io.github.dohex.openrose.androidintegration.HostRejection
import io.github.dohex.openrose.androidintegration.HostSettings
import io.github.dohex.openrose.androidintegration.HostSettingsCodec
import io.github.dohex.openrose.androidintegration.HostStatus
import io.github.dohex.openrose.androidintegration.IslandPrompt
import io.github.dohex.openrose.androidintegration.OwnerGeneration
import io.github.dohex.openrose.androidintegration.OwnerHolder
import io.github.dohex.openrose.androidintegration.PresentationReading
import io.github.dohex.openrose.androidintegration.ReleaseReceipt
import io.github.dohex.openrose.androidintegration.ReleaseResult
import io.github.dohex.openrose.androidintegration.SwitchRequest
import io.github.dohex.openrose.androidintegration.SystemBatteryState
import io.github.dohex.openrose.androidintegration.SystemEndpointDiagnostics
import io.github.dohex.openrose.androidintegration.SystemNoiseState
import io.github.dohex.openrose.androidintegration.SystemPresentationState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.transport.DeviceId

/** Wire form of the module-host protocol. Every IPC binding carries these rendered strings. */
object HostWire {
    fun encode(generation: OwnerGeneration): Wire = obj(
        "incarnation" to str(generation.incarnation),
        "sequence" to num(generation.sequence),
    )
    fun decodeGeneration(wire: Wire): OwnerGeneration = wire.asObj().let { OwnerGeneration(it.str("incarnation"), it.long("sequence")) }

    fun encode(holder: OwnerHolder): Wire = obj(
        "way" to Wire.Str(holder.way.name),
        "generation" to encode(holder.generation),
        "package" to str(holder.packageName),
        "process" to str(holder.processName),
        "uid" to holder.uid?.let(::num),
        "pid" to holder.pid?.let(::num),
    )

    fun decodeHolder(wire: Wire): OwnerHolder = wire.asObj().let {
        OwnerHolder(
            it.field("way").asEnum<ControlWay>(),
            decodeGeneration(it.field("generation")),
            it.str("package"),
            it.str("process"),
            it.optional("uid")?.asInt(),
            it.optional("pid")?.asInt(),
        )
    }

    fun encode(status: HostStatus): Wire = when (status) {
        is HostStatus.Active -> obj("t" to str("active"), "generation" to encode(status.generation), "holder" to encode(status.holder))

        is HostStatus.Inactive -> obj(
            "t" to str("inactive"),
            "incarnation" to str(status.incarnation),
            "last" to status.lastGeneration?.let(::encode),
            "reason" to str(status.reason),
        )
    }

    fun decodeStatus(wire: Wire): HostStatus {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "active" -> HostStatus.Active(decodeGeneration(node.field("generation")), decodeHolder(node.field("holder")))
            "inactive" -> HostStatus.Inactive(node.str("incarnation"), node.optional("last")?.let(::decodeGeneration), node.str("reason"))
            else -> throw WireFormatException("未知宿主状态 $kind")
        }
    }

    fun encode(request: SwitchRequest): Wire = obj(
        "id" to str(request.id),
        "from" to Wire.Str(request.from.name),
        "to" to Wire.Str(request.to.name),
        "generation" to request.fromGeneration?.let(::encode),
    )

    fun decodeRequest(wire: Wire): SwitchRequest = wire.asObj().let {
        SwitchRequest(
            it.str("id"),
            it.field("from").asEnum<ControlWay>(),
            it.field("to").asEnum<ControlWay>(),
            it.optional("generation")?.let(::decodeGeneration),
        )
    }

    fun encode(settings: HostSettings): Wire = Wire.Obj(HostSettingsCodec.encode(settings).mapValues { (_, value) -> Wire.Str(value) })

    fun decodeSettings(wire: Wire): HostSettings = HostSettingsCodec.decode(wire.asObj().fields.mapValues { (_, value) -> value.asStr() })

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
            "setting" to Wire.Str(command.setting.name),
            "value" to ControlWire.encode(command.value),
        )

        is HostCommand.UpdateSettings -> obj("t" to str("settings"), "settings" to encode(command.settings))

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
                node.field("setting").asEnum<DeviceSetting>(),
                ControlWire.decodeValue(node.field("value")),
            )

            "settings" -> HostCommand.UpdateSettings(decodeSettings(node.field("settings")))

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

    fun encode(frame: HostFrame): Wire = obj(
        "generation" to encode(frame.generation),
        "holder" to encode(frame.holder),
        "owned" to ControlWire.encodeOwned(frame.platform, frame.state),
        "presentation" to encodePresentation(frame.presentation),
        "facts" to encode(frame.facts),
    )

    fun decodeFrame(wire: Wire): HostFrame {
        val node = wire.asObj()
        val owned = ControlWire.decodeOwned(node.field("owned"))
        return HostFrame(
            decodeGeneration(node.field("generation")),
            decodeHolder(node.field("holder")),
            owned.platform,
            owned.state,
            decodePresentation(node.field("presentation")),
            decodeFacts(node.field("facts")),
        )
    }

    private fun encodePresentation(state: SystemPresentationState): Wire = obj(
        "reference" to state.reference?.let(ControlWire::encode), "tag" to state.notificationTag?.let(::str),
        "mode" to Wire.Str(state.islandMode.name), "focus" to bool(state.focusNotificationEnabled),
        "islandFields" to bool(state.allowIslandFields), "target" to Wire.Str(state.statusNotificationTarget.name),
        "dynamic" to bool(state.dynamicColor),
        "battery" to state.battery?.let { battery ->
            obj(
                "left" to encodeReading(battery.left, ::num),
                "right" to encodeReading(battery.right, ::num),
                "case" to encodeReading(battery.case, ::num),
            )
        },
        "noise" to state.noise?.let { noise ->
            obj(
                "mode" to encodeReading(noise.mode, ::str),
                "anc" to encodeReading(noise.ancLevel, ::num),
                "transparency" to encodeReading(noise.transparencyLevel, ::num),
            )
        },
        "actions" to arr(state.actions.map { Wire.Str(it.name) }),
        "prompt" to state.prompt?.let { prompt ->
            obj(
                "id" to str(prompt.id),
                "trigger" to Wire.Str(prompt.trigger.name),
                "mode" to Wire.Str(prompt.mode.name),
                "reference" to ControlWire.encode(prompt.reference),
                "claimed" to bool(prompt.claimed),
                "duration" to num(prompt.durationMillis),
            )
        },
    )

    private fun decodePresentation(wire: Wire): SystemPresentationState = wire.asObj().let { node ->
        SystemPresentationState(
            reference = node.optional("reference")?.let(ControlWire::decodeReference),
            notificationTag = node.optional("tag")?.asStr(), islandMode = node.field("mode").asEnum(),
            focusNotificationEnabled = node.bool("focus"), allowIslandFields = node.bool("islandFields"),
            statusNotificationTarget = node.field("target").asEnum(), dynamicColor = node.bool("dynamic"),
            battery = node.optional("battery")?.asObj()?.let {
                SystemBatteryState(
                    decodeReading(it.field("left"), Wire::asInt),
                    decodeReading(it.field("right"), Wire::asInt),
                    decodeReading(it.field("case"), Wire::asInt),
                )
            },
            noise = node.optional("noise")?.asObj()?.let {
                SystemNoiseState(
                    decodeReading(it.field("mode"), Wire::asStr),
                    decodeReading(it.field("anc"), Wire::asInt),
                    decodeReading(it.field("transparency"), Wire::asInt),
                )
            },
            actions = node.arr("actions").map { it.asEnum<DeviceSetting>() }.toSet(),
            prompt = node.optional("prompt")?.asObj()?.let {
                IslandPrompt(
                    it.str("id"),
                    it.field("trigger").asEnum(),
                    it.field("mode").asEnum(),
                    ControlWire.decodeReference(it.field("reference")),
                    it.bool("claimed"),
                    it.long("duration"),
                )
            },
        )
    }

    private fun <T> encodeReading(reading: PresentationReading<T>, encode: (T) -> Wire): Wire = when (reading) {
        PresentationReading.Loading -> obj("t" to str("loading"))
        PresentationReading.Unknown -> obj("t" to str("unknown"))
        PresentationReading.NotProvided -> obj("t" to str("none"))
        is PresentationReading.Known -> obj("t" to str("known"), "v" to encode(reading.value))
    }

    private fun <T> decodeReading(wire: Wire, decode: (Wire) -> T): PresentationReading<T> = wire.asObj().let {
        when (val kind = it.str("t")) {
            "loading" -> PresentationReading.Loading
            "unknown" -> PresentationReading.Unknown
            "none" -> PresentationReading.NotProvided
            "known" -> PresentationReading.Known(decode(it.field("v")))
            else -> throw WireFormatException("未知呈现读数 $kind")
        }
    }

    private fun encode(facts: HostFacts): Wire = obj(
        "presentation" to Wire.Obj(facts.presentation.mapValues { (_, value) -> Wire.Str(value) }),
        "endpoints" to Wire.Obj(
            facts.endpoints.mapValues { (_, endpoint) ->
                obj(
                    "package" to str(endpoint.targetPackage),
                    "process" to str(endpoint.targetProcess),
                    "version" to str(endpoint.componentVersion),
                    "entry" to str(endpoint.entryMatch),
                    "ipc" to Wire.Str(endpoint.ipc.name),
                    "features" to Wire.Obj(endpoint.features.mapValues { (_, value) -> Wire.Str(value) }),
                )
            },
        ),
    )

    private fun decodeFacts(wire: Wire): HostFacts = wire.asObj().let { node ->
        HostFacts(
            endpoints = node.field("endpoints").asObj().fields.mapValues { (_, value) ->
                val endpoint = value.asObj()
                SystemEndpointDiagnostics(
                    endpoint.str("package"),
                    endpoint.str("process"),
                    endpoint.str("version"),
                    endpoint.str("entry"),
                    endpoint.field("ipc").asEnum<BridgeIpcState>(),
                    endpoint.field("features").asObj().fields.mapValues { (_, text) -> text.asStr() },
                )
            },
            presentation = node.field("presentation").asObj().fields.mapValues { (_, text) -> text.asStr() },
        )
    }

    fun encode(receipt: ReleaseReceipt): Wire = obj(
        "request" to str(receipt.requestId),
        "incarnation" to str(receipt.incarnation),
        "generation" to receipt.generation?.let(::encode),
        "predecessorEnded" to bool(receipt.predecessorEnded),
    )

    private fun decodeReceipt(wire: Wire): ReleaseReceipt = wire.asObj().let {
        ReleaseReceipt(
            it.str("request"),
            it.str("incarnation"),
            it.optional("generation")?.let(::decodeGeneration),
            it.bool("predecessorEnded"),
        )
    }

    fun encode(result: ReleaseResult): Wire = when (result) {
        is ReleaseResult.Released -> obj("t" to str("released"), "receipt" to encode(result.receipt))

        is ReleaseResult.Refused -> obj(
            "t" to str("refused"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
            "current" to result.current?.let(::encode),
        )
    }

    fun decodeReleaseResult(wire: Wire): ReleaseResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "released" -> ReleaseResult.Released(decodeReceipt(node.field("receipt")))

            "refused" -> ReleaseResult.Refused(
                node.field("rejection").asEnum<HostRejection>(),
                node.str("message"),
                node.optional("current")?.let(::decodeStatus),
            )

            else -> throw WireFormatException("未知释放结果 $kind")
        }
    }

    fun encode(result: AcquireResult): Wire = when (result) {
        is AcquireResult.Acquired -> obj(
            "t" to str("acquired"),
            "generation" to encode(result.generation),
            "holder" to encode(result.holder),
        )

        is AcquireResult.Refused -> obj(
            "t" to str("refused"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
        )
    }

    fun decodeAcquireResult(wire: Wire): AcquireResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "acquired" -> AcquireResult.Acquired(decodeGeneration(node.field("generation")), decodeHolder(node.field("holder")))
            "refused" -> AcquireResult.Refused(node.field("rejection").asEnum<HostRejection>(), node.str("message"))
            else -> throw WireFormatException("未知获取结果 $kind")
        }
    }

    fun encode(result: HostOpenResult): Wire = when (result) {
        is HostOpenResult.Opened -> obj(
            "t" to str("opened"),
            "lease" to str(result.lease.id),
            "role" to Wire.Str(result.lease.role.name),
            "incarnation" to str(result.lease.incarnation),
            "status" to encode(result.status),
        )

        is HostOpenResult.Refused -> obj(
            "t" to str("refused"),
            "rejection" to Wire.Str(result.rejection.name),
            "message" to str(result.message),
        )
    }

    fun decodeOpenResult(wire: Wire): HostOpenResult {
        val node = wire.asObj()
        return when (val kind = node.str("t")) {
            "opened" -> HostOpenResult.Opened(
                HostLease(node.str("lease"), node.field("role").asEnum<ClientRole>(), node.str("incarnation")),
                decodeStatus(node.field("status")),
            )

            "refused" -> HostOpenResult.Refused(node.field("rejection").asEnum<HostRejection>(), node.str("message"))

            else -> throw WireFormatException("未知授权结果 $kind")
        }
    }

    /** A command addressed to one owner generation. */
    fun encodeCall(generation: OwnerGeneration, command: HostCommand): Wire = obj(
        "generation" to encode(generation),
        "command" to encode(command),
    )

    fun decodeCall(wire: Wire): Pair<OwnerGeneration, HostCommand> = wire.asObj()
        .let { decodeGeneration(it.field("generation")) to decodeCommand(it.field("command")) }

    fun encodeAcquire(request: SwitchRequest, settings: HostSettings): Wire = obj(
        "request" to encode(request),
        "settings" to encode(settings),
    )

    fun decodeAcquire(wire: Wire): Pair<SwitchRequest, HostSettings> = wire.asObj()
        .let { decodeRequest(it.field("request")) to decodeSettings(it.field("settings")) }
}

package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlErrorCode
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.toPersistentList

data class HostPlatformFacts(
    val permission: PermissionState,
    val transport: TransportAvailability,
    val scanPermission: PermissionState = PermissionState.NOT_REQUIRED,
)

data class OwnedControlState(val platform: HostPlatformFacts, val state: ControlState)

object ControlWire {
    fun encodeOwned(platform: HostPlatformFacts, state: ControlState): Wire = obj(
        "permission" to Wire.Str(platform.permission.name),
        "scanPermission" to Wire.Str(platform.scanPermission.name),
        "transport" to encode(platform.transport),
        "connection" to encode(state.connection),
        "scan" to encode(state.scan),
        "candidates" to arr(state.candidates.map(::encode)),
        "bonded" to arr(state.bondedDevices.map(::encode)),
        "device" to state.device?.let(::encode),
        "error" to state.lastError?.let(::encode),
        "operations" to arr(state.settingOperations.map(::encode)),
        "selection" to state.transportSelection?.let(::encode),
        "recovery" to state.connectionRecovery?.let(::encode),
        "target" to state.connectionTarget?.let(::encode),
        "audioDevice" to state.audioDeviceId?.value?.let(::str),
    )

    fun decodeOwned(wire: Wire): OwnedControlState {
        val node = wire.asObj()
        return OwnedControlState(
            platform = HostPlatformFacts(
                node.field("permission").asEnum(),
                decodeTransport(node.field("transport")),
                node.field("scanPermission").asEnum(),
            ),
            state = ControlState(
                platform = io.github.dohex.openrose.core.PlatformState(PermissionState.NOT_REQUIRED),
                connection = decodeConnection(node.field("connection")),
                scan = decodeScan(node.field("scan")),
                candidates = node.arr("candidates").map(::decodeCandidate).toPersistentList(),
                bondedDevices = node.arr("bonded").map(::decodeCandidate).toPersistentList(),
                device = node.optional("device")?.let(::decodeDevice),
                lastError = node.optional("error")?.let(::decodeError),
                settingOperations = node.arr("operations").map(::decodeOperation).toPersistentList(),
                transportSelection = node.optional("selection")?.let(::decodeSelection),
                connectionRecovery = node.optional("recovery")?.let(::decodeRecovery),
                connectionTarget = node.optional("target")?.let(::decodeCandidate),
                audioDeviceId = node.optional("audioDevice")?.let { DeviceId(it.asStr()) },
            ),
        )
    }

    fun encode(reference: SessionReference): Wire = obj("device" to str(reference.deviceId.value), "session" to num(reference.sessionId))
    fun decodeReference(wire: Wire): SessionReference = wire.asObj()
        .let { SessionReference(DeviceId(it.str("device")), it.long("session")) }

    fun encode(error: ControlError): Wire = obj("code" to Wire.Str(error.code.name), "message" to str(error.message))
    fun decodeError(wire: Wire): ControlError = wire.asObj()
        .let { ControlError(it.field("code").asEnum<ControlErrorCode>(), it.str("message")) }

    fun encode(result: OperationResult): Wire = when (result) {
        OperationResult.Success -> obj("ok" to bool(true))
        is OperationResult.Failure -> obj("ok" to bool(false), "error" to encode(result.error))
    }

    fun decodeResult(wire: Wire): OperationResult = wire.asObj().let {
        if (it.bool("ok")) OperationResult.Success else OperationResult.Failure(decodeError(it.field("error")))
    }

    fun encode(value: FieldValue): Wire = when (value) {
        is FieldValue.Number -> obj("t" to str("number"), "v" to num(value.value))

        is FieldValue.Toggle -> obj("t" to str("toggle"), "v" to bool(value.value))

        is FieldValue.Text -> obj("t" to str("text"), "v" to str(value.value))

        is FieldValue.IntegerList -> obj("t" to str("ints"), "v" to arr(value.values.map(::num)))

        is FieldValue.Firmware -> obj("t" to str("firmware"), "parts" to arr(value.parts.map(::num)), "text" to str(value.displayText))

        is FieldValue.TouchKeys -> obj(
            "t" to str("keys"),
            "v" to arr(
                value.bindings.map {
                    obj("side" to str(it.side), "gesture" to str(it.gesture), "action" to it.action?.let(::str), "raw" to num(it.rawAction))
                },
            ),
        )

        is FieldValue.TouchAssignment -> obj(
            "t" to str("assign"),
            "side" to str(value.side),
            "gesture" to str(value.gesture),
            "action" to str(value.action),
        )

        is FieldValue.NoiseCycle -> obj(
            "t" to str("cycle"),
            "v" to Wire.Obj(value.flags.mapValues { (_, flag) -> flag?.let(::bool) ?: Wire.Null }),
        )
    }

    fun decodeValue(wire: Wire): FieldValue {
        val node = wire.asObj()
        return when (val type = node.str("t")) {
            "number" -> FieldValue.Number(node.int("v"))

            "toggle" -> FieldValue.Toggle(node.bool("v"))

            "text" -> FieldValue.Text(node.str("v"))

            "ints" -> FieldValue.IntegerList(node.arr("v").map { it.asInt() })

            "firmware" -> FieldValue.Firmware(node.arr("parts").map { it.asInt() }, node.str("text"))

            "keys" -> FieldValue.TouchKeys(
                node.arr("v").map {
                    val binding = it.asObj()
                    TouchBinding(binding.str("side"), binding.str("gesture"), binding.optional("action")?.asStr(), binding.int("raw"))
                },
            )

            "assign" -> FieldValue.TouchAssignment(node.str("side"), node.str("gesture"), node.str("action"))

            "cycle" -> FieldValue.NoiseCycle(
                node.field("v").asObj().fields.mapValues { (_, flag) ->
                    if (flag == Wire.Null) null else flag.asBool()
                },
            )

            else -> throw WireFormatException("未知字段值类型 $type")
        }
    }
}

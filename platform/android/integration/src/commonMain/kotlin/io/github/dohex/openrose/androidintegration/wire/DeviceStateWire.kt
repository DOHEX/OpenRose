package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetDiagnostics
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

private fun encode(reading: FieldReading): Wire = when (reading) {
    FieldReading.Loading -> obj("r" to str("loading"))
    is FieldReading.NotProvided -> obj("r" to str("none"), "raw" to str(reading.rawHex))
    is FieldReading.Unknown -> obj("r" to str("unknown"), "raw" to str(reading.rawHex))
    is FieldReading.Known -> obj("r" to str("known"), "raw" to str(reading.rawHex), "value" to ControlWire.encode(reading.value))
}

private val BATTERY_FIELDS = setOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)

/** Headset decoders only confirm 0..100 battery levels; anything else across IPC is a malformed frame. */
private fun decodeReading(field: DeviceField, wire: Wire): FieldReading {
    val node = wire.asObj()
    return when (val kind = node.str("r")) {
        "loading" -> FieldReading.Loading
        "none" -> FieldReading.NotProvided(node.str("raw"))
        "unknown" -> FieldReading.Unknown(node.str("raw"))
        "known" -> {
            val value = ControlWire.decodeValue(node.field("value"))
            if (field in BATTERY_FIELDS && (value !is FieldValue.Number || value.value !in 0..100)) {
                throw WireFormatException("电量读数超出 0～100")
            }
            FieldReading.Known(value, node.str("raw"))
        }
        else -> throw WireFormatException("未知读数类型 $kind")
    }
}

internal fun encode(candidate: DeviceCandidate): Wire = obj(
    "id" to str(candidate.deviceId.value),
    "name" to candidate.name?.let(::str),
    "rssi" to candidate.rssi?.let(::num),
    "profile" to candidate.profile?.let { Wire.Str(it.name) },
    "manufacturer" to str(candidate.manufacturerDataHex),
    "classic" to candidate.classicAddress?.let(::str),
)

internal fun decodeCandidate(wire: Wire): DeviceCandidate = wire.asObj().let {
    DeviceCandidate(
        DeviceId(it.str("id")),
        it.optional("name")?.asStr(),
        it.optional("rssi")?.asInt(),
        it.optional("profile")?.asEnum<ModelProfile>(),
        it.str("manufacturer"),
        it.optional("classic")?.asStr(),
    )
}

internal fun encode(device: DeviceState): Wire = obj(
    "id" to str(device.deviceId.value), "session" to num(device.sessionId), "profile" to Wire.Str(device.profile.name),
    "capabilities" to encode(device.capabilities),
    "fields" to arr(
        device.diagnostics.fields.map { (field, reading) ->
            obj("f" to Wire.Str(field.name), "reading" to encode(reading))
        },
    ),
    "fieldErrors" to arr(
        device.diagnostics.fieldErrors.map { (field, error) ->
            obj("f" to Wire.Str(field.name), "error" to ControlWire.encode(error))
        },
    ),
    "raw" to arr(device.diagnostics.rawNotifications.map(::str)), "unknown" to arr(device.diagnostics.unknownNotifications.map(::str)),
    "warnings" to arr(device.diagnostics.warnings.map(::str)), "classic" to device.classicAddress?.let(::str),
)

internal fun decodeDevice(wire: Wire): DeviceState = wire.asObj().let { node ->
    DeviceState(
        deviceId = DeviceId(node.str("id")),
        sessionId = node.long("session"),
        profile = node.field("profile").asEnum(),
        capabilities = decodeCapabilities(node.field("capabilities")),
        diagnostics = HeadsetDiagnostics(
            fields = node.arr("fields").associate {
                val entry = it.asObj()
                val field = entry.field("f").asEnum<DeviceField>()
                field to decodeReading(field, entry.field("reading"))
            }.toPersistentMap(),
            fieldErrors = node.arr("fieldErrors").associate {
                val entry = it.asObj()
                entry.field("f").asEnum<DeviceField>() to ControlWire.decodeError(entry.field("error"))
            }.toPersistentMap(),
            rawNotifications = node.arr("raw").map { it.asStr() }.toPersistentList(),
            unknownNotifications = node.arr("unknown").map { it.asStr() }.toPersistentList(),
            warnings = node.arr("warnings").map { it.asStr() }.toPersistentList(),
        ),
        classicAddress = node.optional("classic")?.asStr(),
    )
}

internal fun encode(operation: SettingOperation): Wire = obj(
    "id" to num(operation.id), "session" to num(operation.sessionId), "setting" to Wire.Str(operation.setting.name),
    "value" to ControlWire.encode(operation.requestedValue), "stage" to Wire.Str(operation.stage.name),
    "error" to operation.error?.let(ControlWire::encode), "written" to num(operation.writtenFrames), "total" to num(operation.totalFrames),
    "confirmed" to arr(operation.confirmedFields.map { Wire.Str(it.name) }),
)

internal fun decodeOperation(wire: Wire): SettingOperation = wire.asObj().let { node ->
    SettingOperation(
        id = node.long("id"), sessionId = node.long("session"), setting = node.field("setting").asEnum(),
        requestedValue = ControlWire.decodeValue(node.field("value")), stage = node.field("stage").asEnum<SettingStage>(),
        error = node.optional("error")?.let(ControlWire::decodeError), writtenFrames = node.int("written"), totalFrames = node.int("total"),
        confirmedFields = node.arr("confirmed").map { it.asEnum<DeviceField>() }.toPersistentSet(),
    )
}

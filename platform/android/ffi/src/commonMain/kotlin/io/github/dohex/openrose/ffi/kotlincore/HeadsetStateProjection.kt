package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetDiagnostics
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import uniffi.openrose_control.HeadsetState as RustHeadsetState
import uniffi.openrose_headset.FieldReading as RustFieldReading
import uniffi.openrose_headset.FieldValue as RustFieldValue
import uniffi.openrose_headset.TouchBinding as RustTouchBinding

internal fun RustHeadsetState.toKotlin(): DeviceState = DeviceState(
    deviceId = DeviceId(headsetId),
    sessionId = sessionId.toLong(),
    profile = readings.profile.toKotlin(),
    capabilities = readings.capabilities.toKotlin(),
    diagnostics = HeadsetDiagnostics(
        fields = readings.fields.entries
            .associate { (field, reading) -> field.toKotlin() to reading.toKotlin() }
            .toPersistentMap(),
        fieldErrors = diagnostics.fieldErrors.entries
            .associate { (field, error) -> field.toKotlin() to error.toKotlin() }
            .toPersistentMap(),
        rawNotifications = diagnostics.rawNotifications.toPersistentList(),
        unknownNotifications = diagnostics.unknownNotifications.toPersistentList(),
        warnings = diagnostics.warnings.toPersistentList(),
    ),
    classicAddress = classicAddress,
)

private fun RustFieldReading.toKotlin(): FieldReading = when (this) {
    RustFieldReading.Loading -> FieldReading.Loading
    is RustFieldReading.NotProvided -> FieldReading.NotProvided(rawHex)
    is RustFieldReading.Unknown -> FieldReading.Unknown(rawHex)
    is RustFieldReading.Known -> FieldReading.Known(value.toKotlin(), rawHex)
}

internal fun RustFieldValue.toKotlin(): FieldValue = when (this) {
    is RustFieldValue.Number -> FieldValue.Number(v1)
    is RustFieldValue.Toggle -> FieldValue.Toggle(v1)
    is RustFieldValue.Text -> FieldValue.Text(v1)
    is RustFieldValue.IntegerList -> FieldValue.IntegerList(v1)
    is RustFieldValue.Firmware -> FieldValue.Firmware(v1.parts, v1.displayText)
    is RustFieldValue.TouchKeys -> FieldValue.TouchKeys(v1.map { it.toKotlin() })
    is RustFieldValue.TouchAssignment -> FieldValue.TouchAssignment(side, gesture, action)
    is RustFieldValue.NoiseCycle -> FieldValue.NoiseCycle(v1)
}

private fun RustTouchBinding.toKotlin(): TouchBinding = TouchBinding(side, gesture, action, rawAction)

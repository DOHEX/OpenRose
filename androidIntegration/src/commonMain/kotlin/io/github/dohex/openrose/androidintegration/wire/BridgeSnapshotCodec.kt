package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.systemFields
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

/**
 * Explicit primitive-only tree keeps Kotlin classes and protocol numbers out of the IPC API. Values are String, Int, Long,
 * Boolean, nested maps and lists of String, Int or maps, so the Android boundary can pick the matching Bundle put call.
 * [capturedElapsedMillis] is the device's elapsed-realtime clock at capture; the reader judges freshness against it.
 */
fun DeviceState.toBridgeSnapshot(hostEpoch: String, readSequence: Long, capturedElapsedMillis: Long): Map<String, Any?> = buildMap {
    put(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
    put(BridgeWire.HOST_EPOCH, hostEpoch)
    put(BridgeWire.DEVICE_ID, deviceId.value)
    normalizeBluetoothAddress(deviceId.value)?.let { put("device_address", it) }
    normalizeBluetoothAddress(classicAddress)?.let { put("classic_address", it) }
    put(BridgeWire.SESSION_ID, sessionId)
    put(BridgeWire.READ_SEQUENCE, readSequence)
    put(BridgeWire.CAPTURED_ELAPSED_MILLIS, capturedElapsedMillis)
    put("profile", profile.name)
    put("fields", systemFields().entries.associate { (field, reading) -> field.name to reading.toBridgeReading() })
    put(
        "capabilities",
        buildMap<String, Any?> {
            put("readable_fields", capabilities.readableFields.map { it.name }.sorted())
            put("writable_settings", capabilities.writableSettings.map { it.name }.sorted())
            capabilities.noiseControl?.let { noise ->
                put(
                    "noise",
                    buildMap<String, Any?> {
                        put("modes", noise.modes.sorted())
                        noise.levelCapabilities(DeviceSetting.ANC_LEVEL)?.let { level -> put("anc_level", level.toBridgeCapability()) }
                        noise.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL)?.let { level ->
                            put("transparency_level", level.toBridgeCapability())
                        }
                    },
                )
            }
        },
    )
}

private fun NoiseLevelCapabilities.toBridgeCapability(): Map<String, Any?> = mapOf(
    "levels" to levels.sorted(),
    "visible_modes" to visibleModes.sorted(),
)

private fun FieldReading.toBridgeReading(): Map<String, Any?> = when (this) {
    FieldReading.Loading -> mapOf(BridgeWire.STATUS to "LOADING")

    is FieldReading.NotProvided -> mapOf(BridgeWire.STATUS to "NOT_PROVIDED")

    is FieldReading.Unknown -> mapOf(BridgeWire.STATUS to "UNKNOWN")

    is FieldReading.Known -> when (val known = value) {
        is FieldValue.Number -> knownReading("NUMBER", known.value)
        is FieldValue.Toggle -> knownReading("TOGGLE", known.value)
        is FieldValue.Text -> knownReading("TEXT", known.value)
        else -> mapOf(BridgeWire.STATUS to "UNKNOWN")
    }
}

private fun knownReading(type: String, value: Any): Map<String, Any?> = mapOf(
    BridgeWire.STATUS to "KNOWN",
    "value" to mapOf("type" to type, "value" to value),
)

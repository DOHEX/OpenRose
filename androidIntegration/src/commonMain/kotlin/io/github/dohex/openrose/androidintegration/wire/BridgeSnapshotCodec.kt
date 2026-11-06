package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.systemFields
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

private val BLUETOOTH_ADDRESS = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

/**
 * Explicit primitive-only tree keeps Kotlin classes and protocol numbers out of the IPC API. Values are String, Int, Long,
 * Boolean, nested maps and lists of String, Int or maps, so the Android boundary can pick the matching Bundle put call.
 * [capturedElapsedMillis] is the device's elapsed-realtime clock at capture; the reader judges freshness against it.
 */
fun DeviceState.toBridgeSnapshot(hostEpoch: String, readSequence: Long, capturedElapsedMillis: Long): Map<String, Any?> = buildMap {
    put(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
    put(BridgeWire.HOST_EPOCH, hostEpoch)
    put(BridgeWire.DEVICE_ID, deviceId.value)
    deviceId.value.takeIf { it.matches(BLUETOOTH_ADDRESS) }?.let { put("device_address", it.uppercase()) }
    classicAddress?.takeIf { it.matches(BLUETOOTH_ADDRESS) }?.let { put("classic_address", it.uppercase()) }
    put(BridgeWire.SESSION_ID, sessionId)
    put(BridgeWire.READ_SEQUENCE, readSequence)
    put("captured_elapsed_millis", capturedElapsedMillis)
    put("profile", profile.name)
    put("fields", systemFields().entries.associate { (field, reading) -> field.name to reading.toBridgeReading() })
    put(
        "field_errors",
        fieldErrors.entries.associate { (field, error) ->
            field.name to mapOf("code" to error.code.name, BridgeWire.MESSAGE to error.message)
        },
    )
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
                        put("cycle_modes", noise.cycleModes.sorted())
                        put("minimum_cycle_selections", noise.minimumCycleSelections)
                    },
                )
            }
            capabilities.equalizer?.let { eq ->
                put(
                    "equalizer",
                    buildMap<String, Any?> {
                        put("presets", eq.presets.sorted())
                        eq.custom?.let { custom ->
                            put(
                                "custom",
                                mapOf(
                                    "bands_hz" to custom.bandsHz.toList(),
                                    "min_gain" to custom.minGain,
                                    "max_gain" to custom.maxGain,
                                ),
                            )
                        }
                    },
                )
            }
            capabilities.audioControl?.let { audio ->
                put("audio", mapOf("modes" to audio.modes.sorted(), "multipoint_coupled" to audio.multipointCoupled))
            }
            capabilities.touchControl?.let { touch ->
                put(
                    "touch",
                    mapOf(
                        "actions" to touch.actions.sorted(),
                        "slots" to touch.slots.map { slot -> mapOf("side" to slot.side, "gesture" to slot.gesture) },
                    ),
                )
            }
            put("prompt_volume_levels", capabilities.promptVolumeLevels.sorted())
            put("gain_levels", capabilities.gainLevels.sorted())
            put("hearing_protection_levels", capabilities.hearingProtectionLevels.sorted())
            put("bass_boost_levels", capabilities.bassBoostLevels.sorted())
            put("prompt_languages", capabilities.promptLanguages.sorted())
        },
    )
}

private fun NoiseLevelCapabilities.toBridgeCapability(): Map<String, Any?> = mapOf(
    "levels" to levels.sorted(),
    "visible_modes" to visibleModes.sorted(),
)

private fun FieldReading.toBridgeReading(): Map<String, Any?> = buildMap {
    put("raw_hex", rawHex)
    when (val reading = this@toBridgeReading) {
        FieldReading.Loading -> put(BridgeWire.STATUS, "LOADING")

        is FieldReading.NotProvided -> put(BridgeWire.STATUS, "NOT_PROVIDED")

        is FieldReading.Unknown -> put(BridgeWire.STATUS, "UNKNOWN")

        is FieldReading.Known -> {
            put(BridgeWire.STATUS, "KNOWN")
            put("value", reading.value.toBridgeValue())
        }
    }
}

private fun FieldValue.toBridgeValue(): Map<String, Any?> = when (this) {
    is FieldValue.Number -> mapOf("type" to "NUMBER", "value" to value)

    is FieldValue.Toggle -> mapOf("type" to "TOGGLE", "value" to value)

    is FieldValue.Text -> mapOf("type" to "TEXT", "value" to value)

    is FieldValue.IntegerList -> mapOf("type" to "INTEGER_LIST", "value" to values.toList())

    is FieldValue.Firmware -> mapOf("type" to "FIRMWARE", "parts" to parts.toList(), "display" to displayText)

    is FieldValue.TouchKeys -> mapOf(
        "type" to "TOUCH_KEYS",
        "bindings" to bindings.map { binding ->
            mapOf(
                "side" to binding.side,
                "gesture" to binding.gesture,
                "action" to binding.action,
                "raw_action" to binding.rawAction,
            )
        },
    )

    is FieldValue.TouchAssignment -> mapOf("type" to "TOUCH_ASSIGNMENT", "side" to side, "gesture" to gesture, "action" to action)

    is FieldValue.NoiseCycle -> mapOf(
        "type" to "NOISE_CYCLE",
        "flags" to flags.entries.associate { (mode, enabled) ->
            mode to when (enabled) {
                true -> "ENABLED"
                false -> "DISABLED"
                null -> "UNKNOWN"
            }
        },
    )
}

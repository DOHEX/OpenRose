package io.github.dohex.openrose.bridge

import android.os.Bundle
import android.os.SystemClock
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

/** Explicit primitive-only parcels keep Kotlin classes and protocol numbers out of the IPC API. */
internal fun DeviceState.toBridgeSnapshot(hostEpoch: String, readSequence: Long): Bundle = Bundle().apply {
    putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
    putString(BridgeWire.HOST_EPOCH, hostEpoch)
    putString(BridgeWire.DEVICE_ID, deviceId.value)
    putLong(BridgeWire.SESSION_ID, sessionId)
    putLong(BridgeWire.READ_SEQUENCE, readSequence)
    putLong("captured_elapsed_millis", SystemClock.elapsedRealtime())
    putString("profile", profile.name)
    putBundle("fields", Bundle().also { fieldsBundle ->
        fields.forEach { (field, reading) -> fieldsBundle.putBundle(field.name, reading.toBridgeReading()) }
    })
    putBundle("field_errors", Bundle().also { errors ->
        fieldErrors.forEach { (field, error) -> errors.putBundle(field.name, Bundle().apply {
            putString("code", error.code.name)
            putString(BridgeWire.MESSAGE, error.message)
        }) }
    })
    putBundle("capabilities", Bundle().apply {
        putStringArrayList("readable_fields", ArrayList(capabilities.readableFields.map { it.name }.sorted()))
        putStringArrayList("writable_settings", ArrayList(capabilities.writableSettings.map { it.name }.sorted()))
        capabilities.noiseControl?.let { noise -> putBundle("noise", Bundle().apply {
            putStringArrayList("modes", ArrayList(noise.modes.sorted()))
            putIntegerArrayList("levels", ArrayList(noise.levels.sorted()))
            putStringArrayList("cycle_modes", ArrayList(noise.cycleModes.sorted()))
            putInt("minimum_cycle_selections", noise.minimumCycleSelections)
        }) }
        capabilities.equalizer?.let { eq -> putBundle("equalizer", Bundle().apply {
            putStringArrayList("presets", ArrayList(eq.presets.sorted()))
            eq.custom?.let { custom -> putBundle("custom", Bundle().apply {
                putIntegerArrayList("bands_hz", ArrayList(custom.bandsHz))
                putInt("min_gain", custom.minGain)
                putInt("max_gain", custom.maxGain)
            }) }
        }) }
        capabilities.audioControl?.let { audio -> putBundle("audio", Bundle().apply {
            putStringArrayList("modes", ArrayList(audio.modes.sorted()))
            putBoolean("multipoint_coupled", audio.multipointCoupled)
        }) }
        capabilities.touchControl?.let { touch -> putBundle("touch", Bundle().apply {
            putStringArrayList("actions", ArrayList(touch.actions.sorted()))
            putParcelableArrayList("slots", ArrayList(touch.slots.map { slot -> Bundle().apply {
                putString("side", slot.side)
                putString("gesture", slot.gesture)
            } }))
        }) }
        putIntegerArrayList("prompt_volume_levels", ArrayList(capabilities.promptVolumeLevels.sorted()))
        putIntegerArrayList("gain_levels", ArrayList(capabilities.gainLevels.sorted()))
        putIntegerArrayList("hearing_protection_levels", ArrayList(capabilities.hearingProtectionLevels.sorted()))
    })
}

private fun FieldReading.toBridgeReading(): Bundle = Bundle().apply {
    putString("raw_hex", rawHex)
    when (val reading = this@toBridgeReading) {
        FieldReading.Loading -> putString(BridgeWire.STATUS, "LOADING")
        is FieldReading.NotProvided -> putString(BridgeWire.STATUS, "NOT_PROVIDED")
        is FieldReading.Unknown -> putString(BridgeWire.STATUS, "UNKNOWN")
        is FieldReading.Known -> {
            putString(BridgeWire.STATUS, "KNOWN")
            putBundle("value", reading.value.toBridgeValue())
        }
    }
}

private fun FieldValue.toBridgeValue(): Bundle = Bundle().apply {
    when (val value = this@toBridgeValue) {
        is FieldValue.Number -> { putString("type", "NUMBER"); putInt("value", value.value) }
        is FieldValue.Toggle -> { putString("type", "TOGGLE"); putBoolean("value", value.value) }
        is FieldValue.Text -> { putString("type", "TEXT"); putString("value", value.value) }
        is FieldValue.IntegerList -> { putString("type", "INTEGER_LIST"); putIntegerArrayList("value", ArrayList(value.values)) }
        is FieldValue.Firmware -> {
            putString("type", "FIRMWARE")
            putIntegerArrayList("parts", ArrayList(value.parts))
            putString("display", value.displayText)
        }
        is FieldValue.TouchKeys -> {
            putString("type", "TOUCH_KEYS")
            putParcelableArrayList("bindings", ArrayList(value.bindings.map { binding -> Bundle().apply {
                putString("side", binding.side)
                putString("gesture", binding.gesture)
                putString("action", binding.action)
                putInt("raw_action", binding.rawAction)
            } }))
        }
        is FieldValue.TouchAssignment -> {
            putString("type", "TOUCH_ASSIGNMENT")
            putString("side", value.side)
            putString("gesture", value.gesture)
            putString("action", value.action)
        }
        is FieldValue.NoiseCycle -> {
            putString("type", "NOISE_CYCLE")
            putBundle("flags", Bundle().apply {
                value.flags.forEach { (mode, enabled) ->
                    putString(mode, when (enabled) { true -> "ENABLED"; false -> "DISABLED"; null -> "UNKNOWN" })
                }
            })
        }
    }
}

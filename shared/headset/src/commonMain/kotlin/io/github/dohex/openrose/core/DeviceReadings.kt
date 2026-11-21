package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.CapabilityFacts
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading

/** Applies the fields reported together by one reply, retaining other observations for diagnostics. */
fun DeviceState.withReadings(readings: Map<DeviceField, FieldReading>): DeviceState {
    if (readings.isEmpty()) return this
    val fields = diagnostics.fields.builder()
    fields.putAll(readings)
    if (changesPresetBank(readings)) fields[DeviceField.EQ] = FieldReading.Loading

    val facts = CapabilityFacts(fields)
    val next = if (facts == CapabilityFacts(diagnostics.fields)) capabilities else facts.resolve(profile)
    if (next !== capabilities) {
        for (field in capabilities.readableFields - next.readableFields) {
            if (fields[field] == FieldReading.Loading) fields.remove(field)
        }
        for (field in next.readableFields) {
            if (field !in fields) fields[field] = FieldReading.Loading
        }
    }
    val errors = diagnostics.fieldErrors.builder()
    for (field in readings.keys) {
        if (field in next.readableFields) errors.remove(field)
    }
    return copy(capabilities = next, diagnostics = diagnostics.copy(fields = fields.build(), fieldErrors = errors.build()))
}

/** Switching preset banks invalidates the previous EQ unless the same reply supplies its replacement. */
private fun DeviceState.changesPresetBank(readings: Map<DeviceField, FieldReading>): Boolean {
    if (!profile.spec.protocol.refreshEqualizerAfterGameMode || DeviceField.EQ in readings) return false
    val game = readings[DeviceField.GAME_MODE] ?: return false
    val previous = reading(DeviceField.GAME_MODE)
    return previous != null && previous != FieldReading.Loading && previous != game
}

package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet

data class CustomEqualizerCapabilities(val bandsHz: PersistentList<Int>, val minGain: Int, val maxGain: Int)
data class EqualizerCapabilities(val presets: PersistentSet<String>, val custom: CustomEqualizerCapabilities? = null)

private fun firmwareComparisonInteger(firmware: FieldValue.Firmware?): Int? = firmware?.parts
    ?.takeIf { it.size == 3 && it.all { part -> part in 0..255 } }
    ?.joinToString("")?.toIntOrNull()

internal fun firmwareAtLeast(firmware: FieldValue.Firmware?, version: Int): Boolean? {
    val comparable = firmwareComparisonInteger(firmware) ?: return null
    return comparable >= version
}

internal fun promptVolumeLevels(firmware: FieldValue.Firmware?, offSince: Int): IntRange = when (firmwareAtLeast(firmware, offSince)) {
    null -> IntRange.EMPTY
    true -> 0..5
    false -> 1..5
}

val DeviceState.writableCustomCurve: CustomEqualizerCapabilities?
    get() = capabilities.equalizer?.custom?.takeIf {
        DeviceSetting.CUSTOM_EQ in capabilities.writableSettings && it.minGain <= it.maxGain
    }

val DeviceState.writablePresets: Set<String>
    get() = if (DeviceSetting.EQ in capabilities.writableSettings) {
        val presets = capabilities.equalizer?.presets.orEmpty()
        if (writableCustomCurve == null) presets else presets - "custom"
    } else {
        emptySet()
    }

fun DeviceState.confirmedCurve(custom: CustomEqualizerCapabilities): List<Int>? {
    val gains = (knownValue(DeviceField.CUSTOM_EQ) as? FieldValue.IntegerList)?.values ?: return null
    return gains.takeIf { it.size == custom.bandsHz.size && it.all { gain -> gain in custom.minGain..custom.maxGain } }
}

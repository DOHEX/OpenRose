package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet

/** A model's visible band labels and slider bounds; these are not measured acoustic filter centers. */
data class CustomEqualizerCapabilities(val bandsHz: PersistentList<Int>, val minGain: Int, val maxGain: Int)
data class EqualizerCapabilities(val presets: PersistentSet<String>, val custom: CustomEqualizerCapabilities? = null)

/** RoseLink compares concatenated decimal text of three complete unsigned firmware bytes. */
internal fun firmwareComparisonInteger(firmware: FieldValue.Firmware?): Int? = firmware?.parts
    ?.takeIf { it.size == 3 && it.all { part -> part in 0..255 } }
    ?.joinToString("")?.toIntOrNull()

val DeviceState.writableCustomCurve: CustomEqualizerCapabilities?
    get() = capabilities.equalizer?.custom?.takeIf {
        DeviceSetting.CUSTOM_EQ in capabilities.writableSettings && it.bandsHz.isNotEmpty() && it.minGain <= it.maxGain
    }

/** Custom curve selection is a separate write for models with an editor. */
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

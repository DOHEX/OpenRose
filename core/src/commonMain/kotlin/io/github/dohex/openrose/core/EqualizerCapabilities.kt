package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** i7's visible band labels and slider bounds; these are not measured acoustic filter centers. */
data class CustomEqualizerCapabilities(val bandsHz: PersistentList<Int>, val minGain: Int, val maxGain: Int)
data class EqualizerCapabilities(val presets: PersistentSet<String>, val custom: CustomEqualizerCapabilities? = null)

/** RoseLink compares concatenated decimal text of three complete unsigned firmware bytes. */
internal fun firmwareComparisonInteger(firmware: FieldValue.Firmware?): Int? = firmware?.parts
    ?.takeIf { it.size == 3 && it.all { part -> part in 0..255 } }
    ?.joinToString("")?.toIntOrNull()

fun equalizerCapabilities(profile: ModelProfile, firmware: FieldValue.Firmware? = null): EqualizerCapabilities {
    val presets = persistentSetOf("pop", "hifi", "rock", "light")
    // RoseLink compares decimal text formed by concatenating the first three unsigned bytes.
    // Validate the source parts instead of trusting a caller-supplied display label.
    val version = firmwareComparisonInteger(firmware)
    return EqualizerCapabilities(
        if (profile == ModelProfile.I7_RS_V2 && version != null && version >= 152) presets.add("eap") else presets,
        if (profile == ModelProfile.I7_RS_V2) {
            CustomEqualizerCapabilities(
                persistentListOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000),
                -6,
                6,
            )
        } else {
            null
        },
    )
}

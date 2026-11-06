package io.github.dohex.openrose.core

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

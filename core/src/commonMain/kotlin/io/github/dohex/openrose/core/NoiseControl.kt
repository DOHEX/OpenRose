package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

/** Model configuration evidence, separate from read support and hardware verification. */
data class NoiseControlCapabilities(
    val modes: Set<String>,
    val levels: Set<Int>,
    val cycleModes: Set<String>,
    /** No minimum is imposed by the recovered model UI or sender. */
    val minimumCycleSelections: Int = 0,
)

/** The recovered i7 configuration does not firmware-gate its automatic ANC modes. */
@Suppress("UNUSED_PARAMETER")
fun noiseControlCapabilities(profile: ModelProfile, firmware: FieldValue.Firmware? = null): NoiseControlCapabilities {
    val basicModes = setOf("normal", "transparency", "wind", "anc")
    return NoiseControlCapabilities(
        modes = when (profile) {
            ModelProfile.I5_ZT -> basicModes
            ModelProfile.I7_RS_V2 -> basicModes + setOf("auto", "dynamic")
        },
        levels = setOf(1, 3, 5),
        cycleModes = basicModes,
    )
}

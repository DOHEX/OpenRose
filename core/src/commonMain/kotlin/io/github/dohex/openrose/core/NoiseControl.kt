package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/** A supported level control, with its own choices and mode-dependent presentation. */
data class NoiseLevelCapabilities(
    val levels: PersistentSet<Int>,
    val visibleModes: PersistentSet<String>,
) {
    /** Visibility does not impose a protocol precondition on reading or writing the level. */
    fun isVisibleIn(mode: String?): Boolean = levels.isNotEmpty() && mode != null && mode in visibleModes
}

/** Model configuration evidence, separate from read support and hardware verification. */
data class NoiseControlCapabilities(
    val modes: PersistentSet<String>,
    val cycleModes: PersistentSet<String>,
    /** Null means that this model does not offer the corresponding level control. */
    val ancLevel: NoiseLevelCapabilities? = null,
    val transparencyLevel: NoiseLevelCapabilities? = null,
    /** No minimum is imposed by the recovered model UI or sender. */
    val minimumCycleSelections: Int = 0,
) {
    fun levelCapabilities(setting: DeviceSetting): NoiseLevelCapabilities? = when (setting) {
        DeviceSetting.ANC_LEVEL -> ancLevel
        DeviceSetting.TRANSPARENCY_LEVEL -> transparencyLevel
        else -> null
    }
}

/** The recovered i7 configuration does not firmware-gate its automatic ANC modes. */
@Suppress("UNUSED_PARAMETER")
fun noiseControlCapabilities(profile: ModelProfile, firmware: FieldValue.Firmware? = null): NoiseControlCapabilities {
    val basicModes = persistentSetOf("normal", "transparency", "wind", "anc")
    return NoiseControlCapabilities(
        modes = when (profile) {
            ModelProfile.I5_ZT -> basicModes
            ModelProfile.I7_RS_V2 -> basicModes.addAll(listOf("auto", "dynamic"))
        },
        cycleModes = basicModes,
        ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
        transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
    )
}

package io.github.dohex.openrose.core

import kotlinx.collections.immutable.PersistentSet

enum class NoiseMode(val id: String) {
    NORMAL("normal"),
    ANC("anc"),
    TRANSPARENCY("transparency"),
    WIND("wind"),
    AUTO("auto"),
    DEEP("deep"),
    DYNAMIC("dynamic"),
    ;

    companion object {
        fun fromId(id: String): NoiseMode? = entries.firstOrNull { it.id == id }
    }
}

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

val DeviceCapabilities.writableNoiseCycle: NoiseControlCapabilities?
    get() = noiseControl?.takeIf { DeviceSetting.NOISE_CYCLE in writableSettings && it.cycleModes.isNotEmpty() }

package io.github.dohex.openrose.core

import kotlinx.collections.immutable.PersistentSet

enum class NoiseMode(val id: String) {
    NORMAL("normal"),
    ANC("anc"),
    TRANSPARENCY("transparency"),
    WIND("wind"),
    AUTO("auto"),
    DYNAMIC("dynamic"),
    DEEP("deep"),
    ;

    companion object {
        fun fromId(id: String): NoiseMode? = entries.firstOrNull { it.id == id }

        fun displayOrder(id: String): Int = fromId(id)?.ordinal ?: entries.size
    }
}

data class NoiseLevelCapabilities(
    val levels: PersistentSet<Int>,
    val visibleModes: PersistentSet<String>,
) {
    fun isVisibleIn(mode: String?): Boolean = levels.isNotEmpty() && mode != null && mode in visibleModes
}

data class NoiseControlCapabilities(
    val modes: PersistentSet<String>,
    val cycleModes: PersistentSet<String>,
    val ancLevel: NoiseLevelCapabilities? = null,
    val transparencyLevel: NoiseLevelCapabilities? = null,
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

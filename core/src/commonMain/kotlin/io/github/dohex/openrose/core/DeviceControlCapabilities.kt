package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.ModelProfile

data class TouchSlot(val side: String, val gesture: String)
data class TouchControlCapabilities(val slots: Set<TouchSlot>, val actions: Set<String>)
data class AudioControlCapabilities(val modes: Set<String>, val multipointCoupled: Boolean)

fun touchControlCapabilities(profile: ModelProfile): TouchControlCapabilities {
    val slots = setOf("left", "right").flatMap { side ->
        setOf("single", "double", "triple", "long").map { gesture -> TouchSlot(side, gesture) }
    }.toSet()
    val actions = setOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "voice_assistant", "noise_cycle")
    return TouchControlCapabilities(slots, if (profile == ModelProfile.I7_RS_V2) actions + "game_mode" else actions)
}

fun audioControlCapabilities(profile: ModelProfile): AudioControlCapabilities = when (profile) {
    ModelProfile.I5_ZT -> AudioControlCapabilities(setOf("aac_sbc", "ldac"), multipointCoupled = true)
    ModelProfile.I7_RS_V2 -> AudioControlCapabilities(setOf("aac_sbc", "ldac", "lhdc", "lc3"), multipointCoupled = false)
}

/** i7 prompt volume belongs to its later firmware-gated controls slice. */
fun promptVolumeLevels(profile: ModelProfile): Set<Int> = when (profile) {
    ModelProfile.I5_ZT -> (1..5).toSet()
    ModelProfile.I7_RS_V2 -> emptySet()
}

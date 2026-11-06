package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

data class TouchSlot(val side: String, val gesture: String)
data class TouchControlCapabilities(val slots: PersistentSet<TouchSlot>, val actions: PersistentSet<String>)
data class AudioControlCapabilities(val modes: PersistentSet<String>, val multipointCoupled: Boolean)

fun touchControlCapabilities(profile: ModelProfile): TouchControlCapabilities {
    val slots = setOf("left", "right").flatMap { side ->
        setOf("single", "double", "triple", "long").map { gesture -> TouchSlot(side, gesture) }
    }.toPersistentSet()
    val actions = persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "voice_assistant", "noise_cycle")
    return TouchControlCapabilities(slots, if (profile == ModelProfile.I7_RS_V2) actions.add("game_mode") else actions)
}

fun audioControlCapabilities(profile: ModelProfile): AudioControlCapabilities = when (profile) {
    ModelProfile.I5_ZT -> AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true)
    ModelProfile.I7_RS_V2 -> AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac", "lhdc", "lc3"), multipointCoupled = false)
}

fun gainLevels(profile: ModelProfile, firmware: FieldValue.Firmware? = null): PersistentSet<Int> = if (profile == ModelProfile.I7_RS_V2 && (firmwareComparisonInteger(firmware) ?: 0) >= 213) (0..2).toPersistentSet() else persistentSetOf()

fun hearingProtectionLevels(profile: ModelProfile, firmware: FieldValue.Firmware? = null): PersistentSet<Int> = if (profile == ModelProfile.I7_RS_V2 && (firmwareComparisonInteger(firmware) ?: 0) >= 213) (0..6).toPersistentSet() else persistentSetOf()

fun promptVolumeLevels(profile: ModelProfile, firmware: FieldValue.Firmware? = null): PersistentSet<Int> = when (profile) {
    ModelProfile.I5_ZT -> (1..5).toPersistentSet()
    ModelProfile.I7_RS_V2 -> if ((firmwareComparisonInteger(firmware) ?: 0) >= 304) (0..5).toPersistentSet() else (1..5).toPersistentSet()
}

internal fun deviceCapabilities(profile: ModelProfile, firmware: FieldValue.Firmware?): DeviceCapabilities {
    val noise = noiseControlCapabilities(profile, firmware)
    return DeviceCapabilities(
        readableFields = profile.supportedFields.toPersistentSet(),
        writableSettings = (
            setOf(
                DeviceSetting.NOISE_MODE,
                DeviceSetting.NOISE_CYCLE, DeviceSetting.EQ, DeviceSetting.GAME_MODE, DeviceSetting.AUDIO_MODE, DeviceSetting.MULTIPOINT, DeviceSetting.TOUCH_BINDING, DeviceSetting.TOUCH_ENABLED, DeviceSetting.EAR_DETECTION,
            ) +
                listOf(DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL).filter { noise.levelCapabilities(it)?.levels?.isNotEmpty() == true } +
                setOf(DeviceSetting.PROMPT_VOLUME) +
                (if (profile == ModelProfile.I7_RS_V2) setOf(DeviceSetting.CUSTOM_EQ) else emptySet()) +
                (if (gainLevels(profile, firmware).isNotEmpty()) setOf(DeviceSetting.GAIN) else emptySet()) +
                (if (hearingProtectionLevels(profile, firmware).isNotEmpty()) setOf(DeviceSetting.HEARING_PROTECTION) else emptySet())
            ).toPersistentSet(),
        noiseControl = noise,
        equalizer = equalizerCapabilities(profile, firmware),
        touchControl = touchControlCapabilities(profile),
        audioControl = audioControlCapabilities(profile),
        promptVolumeLevels = promptVolumeLevels(profile, firmware),
        gainLevels = gainLevels(profile, firmware),
        hearingProtectionLevels = hearingProtectionLevels(profile, firmware),
    )
}

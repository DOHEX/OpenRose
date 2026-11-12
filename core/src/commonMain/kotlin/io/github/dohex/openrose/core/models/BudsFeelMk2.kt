package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val budsFeelMk2Capabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.RS_COMMON_V2.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.GAME_MODE,
        DeviceSetting.MULTIPOINT,
        DeviceSetting.TOUCH_ENABLED,
        DeviceSetting.NOISE_MODE,
        DeviceSetting.NOISE_CYCLE,
        DeviceSetting.AUDIO_MODE,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.BASS_BOOST,
        DeviceSetting.PROMPT_VOLUME,
    ),
    noiseControl = NoiseControlCapabilities(modes = basicNoiseModes, cycleModes = basicNoiseModes),
    touchControl = TouchControlCapabilities(
        earbudTouchSlots,
        persistentSetOf(
            "none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "game_mode", "voice_assistant", "noise_cycle",
        ),
    ),
    audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac", "lc3"), multipointCoupled = false),
    bassBoostLevels = (0..4).toPersistentSet(),
    // RoseLink's BudsFeel MK2 panel disables the off option and sets no firmware gate.
    promptVolumeLevels = (1..5).toPersistentSet(),
)

internal val budsFeelMk2 = ModelSpec(
    displayName = "BudsFeel MK2", shortLabel = "BudsFeel MK2", family = ProtocolFamily.RS_COMMON_V2,
    advertisementPrefixes = listOf("0b150a0b"), nameBrand = "budsfeel", nameModelNumber = "mk2", nameRequiresModel = true,
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931", writeIntervalMillis = 100, advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE, equalizerPresetLabels = emptyMap(),
    capabilities = { budsFeelMk2Capabilities },
)

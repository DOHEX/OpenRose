package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val openFeelMk2Capabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.RS_COMMON_V2.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.GAME_MODE,
        DeviceSetting.MULTIPOINT,
        DeviceSetting.TOUCH_ENABLED,
        DeviceSetting.AUDIO_MODE,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.BASS_BOOST,
        DeviceSetting.GAIN,
        DeviceSetting.HEARING_PROTECTION,
        DeviceSetting.CUSTOM_EQ,
        DeviceSetting.PROMPT_VOLUME,
    ),
    // Only the custom curve is writable: RoseLink offers the custom EQ panel plus the separate
    // bass-loudness-boost preset, which OpenRose exposes as BASS_BOOST rather than an EQ preset.
    equalizer = EqualizerCapabilities(
        persistentSetOf(),
        // The shared EQ panel's band labels and slider bounds, not measured acoustic filter centers.
        CustomEqualizerCapabilities(persistentListOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000), -6, 6),
    ),
    // RoseLink's OpenFeel MK2 panel offers 1..5 plus the off option (00); it sets no firmware gate.
    promptVolumeLevels = (0..5).toPersistentSet(),
    touchControl = TouchControlCapabilities(
        earbudTouchSlots,
        persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "game_mode", "voice_assistant"),
    ),
    audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac", "lhdc", "lc3"), multipointCoupled = false),
    gainLevels = (0..2).toPersistentSet(),
    hearingProtectionLevels = (0..6).toPersistentSet(),
    bassBoostLevels = (0..4).toPersistentSet(),
)

internal val openFeelMk2 = ModelSpec(
    displayName = "OpenFeel MK2", shortLabel = "OpenFeel MK2", family = ProtocolFamily.RS_COMMON_V2,
    advertisementPrefixes = listOf("0b0eb657"), nameBrand = "openfeel", nameModelNumber = "mk2", nameRequiresModel = true,
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931", writeIntervalMillis = 100, advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE, equalizerPresetLabels = emptyMap(),
    capabilities = { openFeelMk2Capabilities },
)

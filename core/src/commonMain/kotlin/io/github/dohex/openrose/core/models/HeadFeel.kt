package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.firmwareComparisonInteger
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/** HeadFeel, RoseLink Headfree / Zt231 configuration. */
internal val headFeel = ModelSpec(
    displayName = "HeadFeel",
    shortLabel = "HeadFeel",
    family = ProtocolFamily.ZT,
    advertisementPrefixes = listOf("525300020002"),
    nameBrand = "headfeel",
    nameBrandAliases = listOf("headfree"),
    nameModelNumber = "headfeel",
    gatt = GattProfile(
        "011bf5da-0000-1000-8000-00805f9b34fb",
        "00007777-0000-1000-8000-00805f9b34fb",
        "00008888-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931",
    writeIntervalMillis = 100,
    advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    equalizerPresetLabels = emptyMap(),
    ztInitKeys = listOf(
        "0101", "0602", "0201", "0202", "0203", "0603", "0801", "0d01", "0401", "0a01",
        "0103", "0801", "0802", "0803", "0604", "0e03", "0605",
    ),
    ztIndependentMultipoint = true,
    ztLevelWriteOffset = 1,
    ztPromptVolumeDirect = true,
    ztFindStopReadOpcode = true,
    capabilities = ::headFeelCapabilities,
)

private val headFeelTouchControl = TouchControlCapabilities(
    persistentSetOf(
        TouchSlot("left", "single"),
        TouchSlot("left", "double"),
        TouchSlot("left", "triple"),
        TouchSlot("right", "double"),
        TouchSlot("right", "triple"),
    ),
    persistentSetOf(
        "volume_up",
        "volume_down",
        "previous_track",
        "next_track",
        "voice_assistant",
        "play_pause",
        "none",
        "noise_cycle",
    ),
    slotLabels = persistentMapOf(
        TouchSlot("left", "single") to "电源键",
        TouchSlot("left", "double") to "电源键",
        TouchSlot("left", "triple") to "电源键",
        TouchSlot("right", "double") to "ANC键",
        TouchSlot("right", "triple") to "ANC键",
    ),
)

private fun headFeelCapabilities(firmware: FieldValue.Firmware?): DeviceCapabilities {
    // RoseLink gates both the gain level and the custom EQ curve on firmware 130 (customSinceVersion / field_7).
    val firmwareSince130 = (firmwareComparisonInteger(firmware) ?: 0) >= 130
    val settings = persistentSetOf(
        DeviceSetting.NOISE_MODE, DeviceSetting.NOISE_CYCLE, DeviceSetting.EQ, DeviceSetting.GAME_MODE,
        DeviceSetting.AUDIO_MODE, DeviceSetting.MULTIPOINT, DeviceSetting.TOUCH_BINDING, DeviceSetting.TOUCH_ENABLED,
        DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL, DeviceSetting.PROMPT_VOLUME,
    )
    return DeviceCapabilities(
        readableFields = persistentSetOf(
            DeviceField.CASE_BATTERY, DeviceField.FIRMWARE, DeviceField.NOISE_MODE, DeviceField.NOISE_CYCLE,
            DeviceField.EQ, DeviceField.CUSTOM_EQ, DeviceField.GAIN, DeviceField.GAME_MODE, DeviceField.AUDIO_MODE,
            DeviceField.MULTIPOINT, DeviceField.TOUCH_KEYS, DeviceField.TOUCH_ENABLED, DeviceField.ANC_LEVEL,
            DeviceField.TRANSPARENCY_LEVEL, DeviceField.PROMPT_VOLUME,
        ),
        writableSettings = if (firmwareSince130) {
            settings.adding(DeviceSetting.GAIN).adding(DeviceSetting.CUSTOM_EQ)
        } else {
            settings
        },
        noiseControl = NoiseControlCapabilities(
            modes = basicNoiseModes,
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            if (firmwareSince130) persistentSetOf("pop", "hifi", "rock", "custom") else persistentSetOf("pop", "hifi", "rock"),
            // The recovered panel's band labels and slider bounds, not measured acoustic filter centers.
            if (firmwareSince130) {
                CustomEqualizerCapabilities(
                    persistentListOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000),
                    -6,
                    6,
                )
            } else {
                null
            },
        ),
        touchControl = headFeelTouchControl,
        audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac", "lhdc", "ldac_lhdc"), multipointCoupled = false),
        promptVolumeLevels = (1..5).toPersistentSet(),
        gainLevels = if (firmwareSince130) (0..2).toPersistentSet() else persistentSetOf(),
    )
}

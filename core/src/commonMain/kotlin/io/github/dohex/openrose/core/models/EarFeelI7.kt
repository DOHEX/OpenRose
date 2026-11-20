package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.firmwareComparisonInteger
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/** EarFeel i7, RoseLink `rs_common_v2/earfeel_i7_device_config`. */
internal val earFeelI7 = ModelSpec(
    rsV2InfoIds = "0d0c092c2d2a45460e322b01072f082e36",
    displayName = "EarFeel i7",
    shortLabel = "i7",
    family = ProtocolFamily.RS_COMMON_V2,
    advertisementPrefixes = listOf("b8e5290f"),
    nameBrand = "earfeel",
    nameModelNumber = "i7",
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931",
    writeIntervalMillis = 100,
    advertisedClassicAddress = ::rsV2ClassicAddress,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf(
        "pop" to "弱水经典",
        "hifi" to "原声再现",
        "rock" to "古典乐器",
        "light" to "日系甜美",
        "eap" to "欧美流行",
    ),
    capabilities = ::earFeelI7Capabilities,
)

private fun earFeelI7Capabilities(firmware: FieldValue.Firmware?): DeviceCapabilities {
    // Firmware gates compare RoseLink's concatenated decimal text; unread or malformed firmware opens none of them.
    val version = firmwareComparisonInteger(firmware) ?: 0
    val gainAndProtection = version >= 213
    val presets = persistentSetOf("pop", "hifi", "rock", "light")
    return DeviceCapabilities(
        readableFields = rsReadings(
            BatteryLayout.EARBUDS_AND_CASE, DeviceField.CASE_FLAG, DeviceField.TOUCH_KEYS,
            DeviceField.TOUCH_ENABLED, DeviceField.EAR_DETECTION, DeviceField.EQ, DeviceField.CUSTOM_EQ,
            DeviceField.NOISE_MODE, DeviceField.NOISE_CYCLE, DeviceField.ANC_LEVEL,
            DeviceField.TRANSPARENCY_LEVEL, DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT,
            DeviceField.PROMPT_VOLUME, DeviceField.GAIN, DeviceField.HEARING_PROTECTION, DeviceField.FIND_STATE,
        ),
        writableSettings = (
            listOf(
                DeviceSetting.NOISE_MODE,
                DeviceSetting.NOISE_CYCLE,
                DeviceSetting.EQ,
                DeviceSetting.GAME_MODE,
                DeviceSetting.AUDIO_MODE,
                DeviceSetting.MULTIPOINT,
                DeviceSetting.TOUCH_BINDING,
                DeviceSetting.TOUCH_ENABLED,
                DeviceSetting.EAR_DETECTION,
                DeviceSetting.ANC_LEVEL,
                DeviceSetting.TRANSPARENCY_LEVEL,
                DeviceSetting.PROMPT_VOLUME,
                DeviceSetting.CUSTOM_EQ,
            ) + if (gainAndProtection) listOf(DeviceSetting.GAIN, DeviceSetting.HEARING_PROTECTION) else emptyList()
            ).toPersistentSet(),
        noiseControl = NoiseControlCapabilities(
            // The recovered configuration does not firmware-gate its automatic ANC modes.
            modes = basicNoiseModes.addingAll(listOf("auto", "dynamic")),
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            if (version >= 152) presets.adding("eap") else presets,
            // The i7 panel's band labels and slider bounds, not measured acoustic filter centers.
            CustomEqualizerCapabilities(persistentListOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000), -6, 6),
        ),
        touchControl = TouchControlCapabilities(
            earbudTouchSlots,
            persistentSetOf(
                "none",
                "play_pause",
                "previous_track",
                "next_track",
                "volume_up",
                "volume_down",
                "voice_assistant",
                "noise_cycle",
                "game_mode",
            ),
        ),
        audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac", "lhdc", "lc3"), multipointCoupled = false),
        promptVolumeLevels = (if (version >= 304) 0..5 else 1..5).toPersistentSet(),
        gainLevels = if (gainAndProtection) (0..2).toPersistentSet() else persistentSetOf(),
        hearingProtectionLevels = if (gainAndProtection) (0..6).toPersistentSet() else persistentSetOf(),
    )
}

package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.GattProfile
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val rsV2Gatt = GattProfile(
    "000000fe-0000-1000-8000-00805f9b34fb",
    "000000f1-0000-1000-8000-00805f9b34fb",
    "000000f2-0000-1000-8000-00805f9b34fb",
)

internal fun rsV2Model(
    displayName: String,
    prefix: String,
    batteryLayout: BatteryLayout,
    nameBrand: String = displayName.lowercase(),
    nameModelNumber: String = nameBrand,
    nameRequiresModel: Boolean = false,
    nameBrandAliases: List<String> = emptyList(),
    equalizerPresetLabels: Map<String, String> = emptyMap(),
    gameEqualizerPresets: Set<String>? = null,
    refreshEqualizerAfterGameMode: Boolean = false,
    spatialNeedsEarAdaptive: Boolean = false,
    synchronizeClockOnConnect: Boolean = false,
    infoIds: String? = null,
    capabilities: (FieldValue.Firmware?) -> DeviceCapabilities,
) = ModelSpec(
    displayName = displayName, shortLabel = displayName,
    protocol = ModelProtocol.RsCommonV2(
        infoIds = infoIds,
        spatialNeedsEarAdaptive = spatialNeedsEarAdaptive,
        synchronizeClockOnConnect = synchronizeClockOnConnect,
        refreshEqualizerAfterGameMode = refreshEqualizerAfterGameMode,
    ),
    advertisementPrefixes = listOf(prefix), batteryLayout = batteryLayout, nameBrand = nameBrand,
    nameModelNumber = nameModelNumber, nameRequiresModel = nameRequiresModel, nameBrandAliases = nameBrandAliases,
    equalizerPresetLabels = equalizerPresetLabels, capabilities = capabilities, gatt = rsV2Gatt,
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931", writeIntervalMillis = 100,
    advertisedClassicAddress = ::rsV2ClassicAddress, gameEqualizerPresets = gameEqualizerPresets,
)

internal val rsV2Curve = CustomEqualizerCapabilities(
    persistentListOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000),
    -6,
    6,
)

internal fun rsV2Touch(noiseCycle: Boolean): TouchControlCapabilities {
    val actions = persistentSetOf(
        "none",
        "play_pause",
        "previous_track",
        "next_track",
        "volume_up",
        "volume_down",
        "game_mode",
        "voice_assistant",
    )
    return TouchControlCapabilities(earbudTouchSlots, if (noiseCycle) actions.adding("noise_cycle") else actions)
}

internal fun rsV2Capabilities(
    layout: BatteryLayout,
    promptVolume: IntRange,
    noise: NoiseControlCapabilities? = null,
    equalizer: EqualizerCapabilities? = null,
    touch: TouchControlCapabilities? = null,
    audioModes: Set<String> = emptySet(),
    touchEnabled: Boolean = false,
    tap: TouchControlCapabilities? = null,
    earDetection: Boolean = false,
    promptLanguages: Boolean = false,
    gain: Boolean = false,
    protection: Boolean = false,
    bassBoostLevels: IntRange = IntRange.EMPTY,
    findEvents: Boolean = false,
    spatialModes: Set<String> = emptySet(),
    spatialContents: Set<String> = emptySet(),
    earAdaptive: Boolean = false,
    independentMic: Boolean = false,
): DeviceCapabilities {
    val fields = rsReadings(layout).toMutableSet()
    val settings = mutableSetOf<DeviceSetting>()
    fun control(available: Boolean, setting: DeviceSetting, field: DeviceField) {
        if (available) {
            settings.add(setting)
            fields.add(field)
        }
    }
    control(true, DeviceSetting.MULTIPOINT, DeviceField.MULTIPOINT)
    control(true, DeviceSetting.GAME_MODE, DeviceField.GAME_MODE)
    control(true, DeviceSetting.PROMPT_VOLUME, DeviceField.PROMPT_VOLUME)
    control(!noise?.modes.isNullOrEmpty(), DeviceSetting.NOISE_MODE, DeviceField.NOISE_MODE)
    control(!noise?.cycleModes.isNullOrEmpty(), DeviceSetting.NOISE_CYCLE, DeviceField.NOISE_CYCLE)
    control(noise?.ancLevel != null, DeviceSetting.ANC_LEVEL, DeviceField.ANC_LEVEL)
    control(noise?.transparencyLevel != null, DeviceSetting.TRANSPARENCY_LEVEL, DeviceField.TRANSPARENCY_LEVEL)
    control(!equalizer?.presets.isNullOrEmpty(), DeviceSetting.EQ, DeviceField.EQ)
    control(equalizer?.custom != null, DeviceSetting.CUSTOM_EQ, DeviceField.CUSTOM_EQ)
    control(!bassBoostLevels.isEmpty(), DeviceSetting.BASS_BOOST, DeviceField.EQ_SLOT)
    control(touch != null, DeviceSetting.TOUCH_BINDING, DeviceField.TOUCH_KEYS)
    control(touchEnabled, DeviceSetting.TOUCH_ENABLED, DeviceField.TOUCH_ENABLED)
    control(tap != null, DeviceSetting.TAP_ENABLED, DeviceField.TAP_ENABLED)
    control(tap != null, DeviceSetting.TAP_BINDING, DeviceField.TAP_KEYS)
    control(audioModes.isNotEmpty(), DeviceSetting.AUDIO_MODE, DeviceField.AUDIO_MODE)
    control(gain, DeviceSetting.GAIN, DeviceField.GAIN)
    control(protection, DeviceSetting.HEARING_PROTECTION, DeviceField.HEARING_PROTECTION)
    control(earDetection, DeviceSetting.EAR_DETECTION, DeviceField.EAR_DETECTION)
    control(promptLanguages, DeviceSetting.PROMPT_LANGUAGE, DeviceField.PROMPT_LANGUAGE)
    control(spatialModes.isNotEmpty(), DeviceSetting.SPATIAL_MODE, DeviceField.SPATIAL_MODE)
    control(spatialContents.isNotEmpty(), DeviceSetting.SPATIAL_CONTENT, DeviceField.SPATIAL_CONTENT)
    control(earAdaptive, DeviceSetting.EAR_ADAPTIVE, DeviceField.EAR_ADAPTIVE)
    control(independentMic, DeviceSetting.INDEPENDENT_MIC_ACTIVATION, DeviceField.INDEPENDENT_MIC_ACTIVATION)
    control(independentMic, DeviceSetting.INDEPENDENT_MIC_MODE, DeviceField.INDEPENDENT_MIC_MODE)
    if (equalizer != null || !bassBoostLevels.isEmpty()) fields.add(DeviceField.EQ)
    if (layout == BatteryLayout.EARBUDS_AND_CASE) fields.add(DeviceField.CASE_FLAG)
    if (findEvents) fields.add(DeviceField.FIND_STATE)
    return DeviceCapabilities(
        readableFields = fields.toPersistentSet(), writableSettings = settings.toPersistentSet(),
        noiseControl = noise, equalizer = equalizer, touchControl = touch, tapControl = tap,
        audioControl = audioModes.takeIf { it.isNotEmpty() }?.let { AudioControlCapabilities(it.toPersistentSet(), false) },
        promptVolumeLevels = promptVolume.toPersistentSet(), bassBoostLevels = bassBoostLevels.toPersistentSet(),
        promptLanguages = if (promptLanguages) persistentSetOf("chinese", "english") else persistentSetOf(),
        spatialModes = spatialModes.toPersistentSet(), spatialContents = spatialContents.toPersistentSet(),
        gainLevels = if (gain) (0..2).toPersistentSet() else persistentSetOf(),
        hearingProtectionLevels = if (protection) (0..6).toPersistentSet() else persistentSetOf(),
    )
}

internal fun rsV2ClassicAddress(normalizedManufacturerDataHex: String): String? {
    if (normalizedManufacturerDataHex.length < 34) return null
    val normalized = normalizedManufacturerDataHex.uppercase()
    if (normalized.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
    if (normalized.substring(14, 16) != "01") return null
    val octets = normalized.substring(22, 34).chunked(2)
    if (octets.all { it == "00" } || octets.all { it == "FF" }) return null
    return octets.joinToString(":")
}

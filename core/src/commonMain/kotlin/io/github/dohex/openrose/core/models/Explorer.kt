package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val explorerCapabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.F8.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.EQ,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.GAME_MODE,
        DeviceSetting.NOISE_MODE,
        DeviceSetting.AUDIO_MODE,
        DeviceSetting.MULTIPOINT,
        DeviceSetting.PROMPT_LANGUAGE,
        DeviceSetting.EAR_DETECTION,
        DeviceSetting.TOUCH_ENABLED,
        DeviceSetting.ANC_LEVEL,
        DeviceSetting.TRANSPARENCY_LEVEL,
        DeviceSetting.PROMPT_VOLUME,
    ),
    noiseControl = NoiseControlCapabilities(
        modes = persistentSetOf("anc", "normal", "transparency"),
        cycleModes = persistentSetOf(),
        ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
        transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
    ),
    equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
    touchControl = TouchControlCapabilities(earbudTouchSlots, f8Actions),
    audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true),
    promptLanguages = persistentSetOf("chinese", "english"),
    promptVolumeLevels = (1..5).toPersistentSet(),
)

internal val explorer = ModelSpec(
    displayName = "EXPLORER",
    shortLabel = "EXPLORER",
    family = ProtocolFamily.F8,
    advertisementPrefixes = listOf("18840107"),
    nameBrand = "explorer",
    nameModelNumber = "explorer",
    nameRequiresModel = true,
    gatt = GattProfile(
        "0000fdb3-0000-1000-8000-00805f9b34fb",
        "0000ff16-0000-1000-8000-00805f9b34fb",
        "0000ff17-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931",
    writeIntervalMillis = 100,
    advertisedClassicAddress = ::f8Address,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
    capabilities = { explorerCapabilities },
    f8ExtendedInit = true,
    f8NoiseNormalWire = 0,
    f8CollapsedEqRead = false,
)

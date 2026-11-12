package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val ceramicsXCapabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.F8.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.EQ,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.GAME_MODE,
        DeviceSetting.NOISE_MODE,
        DeviceSetting.AUDIO_MODE,
        DeviceSetting.MULTIPOINT,
        DeviceSetting.PROMPT_LANGUAGE,
    ),
    noiseControl = NoiseControlCapabilities(
        modes = persistentSetOf("anc", "normal", "transparency", "wind"),
        cycleModes = persistentSetOf(),
    ),
    equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
    touchControl = TouchControlCapabilities(earbudTouchSlots, f8Actions),
    audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true),
    promptLanguages = persistentSetOf("chinese", "english"),
)

internal val ceramicsX = ModelSpec(
    displayName = "CERAMICS-X",
    shortLabel = "CERAMICS-X",
    family = ProtocolFamily.F8,
    advertisementPrefixes = listOf("18840109"),
    nameBrand = "ceramicsx",
    nameModelNumber = "ceramicsx",
    nameRequiresModel = true,
    gatt = GattProfile(
        "0000fdb3-0000-1000-8000-00805f9b34fb",
        "0000ff16-0000-1000-8000-00805f9b34fb",
        "0000ff17-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = ::f8Address,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
    capabilities = { ceramicsXCapabilities },
    f8ExtendedInit = true,
    f8NoiseNormalWire = 3,
    f8CollapsedEqRead = false,
)

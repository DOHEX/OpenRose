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

/** The recovered EarFree i5 configuration gates nothing on firmware. */
private val earFreeI5Capabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.ZT.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
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
    ),
    noiseControl = NoiseControlCapabilities(
        modes = basicNoiseModes,
        cycleModes = basicNoiseModes,
        ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
        transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
    ),
    equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock", "light")),
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
        ),
    ),
    // One ZT field carries both: LDAC turns multipoint off.
    audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true),
    promptVolumeLevels = (1..5).toPersistentSet(),
)

/** EarFree i5 / EarFeel-i5, RoseLink `zt/earfree_i5_device_config`. */
internal val earFreeI5 = ModelSpec(
    displayName = "EarFree i5",
    shortLabel = "i5",
    family = ProtocolFamily.ZT,
    advertisementPrefixes = listOf("525300010001"),
    nameBrand = "earfree",
    nameModelNumber = "i5",
    gatt = GattProfile(
        "011bf5da-0000-1000-8000-00805f9b34fb",
        "00007777-0000-1000-8000-00805f9b34fb",
        "00008888-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "弱水经典", "hifi" to "日系柔情", "rock" to "乐器大师", "light" to "清新空灵"),
    capabilities = { earFreeI5Capabilities },
)

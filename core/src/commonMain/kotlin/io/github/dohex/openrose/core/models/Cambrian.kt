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
import io.github.dohex.openrose.core.protocol.RsCommonInit
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

internal val cambrian = ModelSpec(
    displayName = "Cambrian",
    shortLabel = "Cambrian",
    family = ProtocolFamily.RS_COMMON,
    advertisementPrefixes = listOf("0b0c0a0c"),
    nameBrand = "cambrian",
    nameModelNumber = "cambrian",
    nameBrandAliases = listOf(),
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931",
    writeIntervalMillis = 100,
    advertisedClassicAddress = { rsCommonClassicAddress(it, 10) },
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
    rsCommonInit = RsCommonInit.CAMBRIAN,
    capabilities = {
        DeviceCapabilities(
            readableFields = ProtocolFamily.RS_COMMON.readableFields.toPersistentSet(),
            writableSettings = persistentSetOf(
                DeviceSetting.GAME_MODE,
                DeviceSetting.TOUCH_BINDING,
                DeviceSetting.MULTIPOINT,
                DeviceSetting.TOUCH_ENABLED,
                DeviceSetting.PROMPT_VOLUME,
                DeviceSetting.NOISE_MODE,
                DeviceSetting.TRANSPARENCY_LEVEL,
                DeviceSetting.EQ,
                DeviceSetting.AUDIO_MODE,
            ),
            noiseControl = NoiseControlCapabilities(
                persistentSetOf("anc", "normal", "transparency", "wind"),
                persistentSetOf(),
                transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 2, 3), persistentSetOf("transparency")),
            ),
            equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
            touchControl = TouchControlCapabilities(
                rsCommonTouchSlots,
                persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down"),
                rsCommonTouchSlots.associateWith { if (it.side == "left") "音量−" else "音量＋" }.toPersistentMap(),
            ),
            audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true),
            promptVolumeLevels = (1..5).toPersistentSet(),
        )
    },
)

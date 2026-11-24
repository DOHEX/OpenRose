package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.RsCommonInit
import kotlinx.collections.immutable.persistentSetOf

internal val budsFreeI = ModelSpec(
    displayName = "BudsFree i",
    shortLabel = "BudsFree i",
    protocol = ModelProtocol.RsCommon(
        initialization = RsCommonInit.BUDS_FREE,
        touchActions = mapOf(
            "none" to 0,
            "play_pause" to 1,
            "previous_track" to 2,
            "next_track" to 3,
            "volume_up" to 5,
            "volume_down" to 6,
        ),
    ),
    advertisementPrefixes = listOf("12cc3e4c"),
    nameBrand = "budsfree",
    nameModelNumber = "i",
    nameRequiresModel = true,
    nameBrandAliases = listOf(),
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = { rsCommonClassicAddress(it, 16) },
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
    capabilities = {
        DeviceCapabilities(
            readableFields = rsReadings(
                BatteryLayout.EARBUDS_ONLY,
                DeviceField.TOUCH_KEYS,
                DeviceField.TOUCH_ENABLED,
                DeviceField.NOISE_MODE,
                DeviceField.EQ,
                DeviceField.AUDIO_MODE,
            ),
            writableSettings = persistentSetOf(
                DeviceSetting.GAME_MODE,
                DeviceSetting.TOUCH_BINDING,
                DeviceSetting.TOUCH_ENABLED,
                DeviceSetting.NOISE_MODE,
                DeviceSetting.EQ,
                DeviceSetting.AUDIO_MODE,
            ),
            noiseControl = NoiseControlCapabilities(persistentSetOf("anc", "normal", "transparency"), persistentSetOf()),
            equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
            touchControl = TouchControlCapabilities(
                rsCommonTouchSlots,
                persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down"),
            ),
            audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true),
        )
    },
)

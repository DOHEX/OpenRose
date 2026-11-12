package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/** EarFree-i3, RoseLink Zt219 configuration. */
internal val earFreeI3 = ModelSpec(
    displayName = "EarFree-i3",
    shortLabel = "i3",
    family = ProtocolFamily.ZT,
    advertisementPrefixes = listOf("f10203", "010203"),
    nameBrand = "earfree",
    nameModelNumber = "i3",
    nameRequiresModel = true,
    gatt = GattProfile(
        "011bf5da-0000-1000-8000-00805f9b34fb",
        "00007777-0000-1000-8000-00805f9b34fb",
        "00008888-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = emptyMap(),
    ztInitKeys = listOf("0101", "0602", "0201", "0603", "0801", "0401", "0a01", "0b01"),
    ztThreeByteNoise = true,
    capabilities = {
        DeviceCapabilities(
            readableFields = persistentSetOf(
                DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY,
                DeviceField.LEFT_CHARGING, DeviceField.RIGHT_CHARGING, DeviceField.NOISE_MODE, DeviceField.EQ,
                DeviceField.GAME_MODE, DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT, DeviceField.TOUCH_KEYS,
                DeviceField.TOUCH_ENABLED, DeviceField.HIDE_SEARCH,
            ),
            writableSettings = persistentSetOf(
                DeviceSetting.NOISE_MODE,
                DeviceSetting.EQ,
                DeviceSetting.GAME_MODE,
                DeviceSetting.AUDIO_MODE,
                DeviceSetting.MULTIPOINT,
                DeviceSetting.TOUCH_BINDING,
                DeviceSetting.TOUCH_ENABLED,
            ),
            noiseControl = NoiseControlCapabilities(
                modes = persistentSetOf("normal", "transparency", "anc"),
                cycleModes = persistentSetOf(),
            ),
            equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
            touchControl = TouchControlCapabilities(
                listOf("left", "right").flatMap { side ->
                    listOf("single", "double", "triple").map { gesture -> TouchSlot(side, gesture) }
                }.toPersistentSet(),
                persistentSetOf("volume_up", "volume_down", "previous_track", "next_track", "play_pause", "none"),
            ),
            audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "ldac"), multipointCoupled = true),
        )
    },
)

package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.RsCommonInit
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

internal val openFeelLhdc = ModelSpec(
    displayName = "OpenFeel",
    shortLabel = "OpenFeel",
    family = ProtocolFamily.RS_COMMON,
    advertisementPrefixes = listOf("0b0a0a0c"),
    nameBrand = "openfeel",
    nameModelNumber = "openfeel",
    nameBrandAliases = listOf("openfree"),
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = { rsCommonClassicAddress(it, 10) },
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "BASS", "hifi" to "BALANCE"),
    rsCommonInit = RsCommonInit.OPEN_FEEL,
    capabilities = {
        DeviceCapabilities(
            readableFields = ProtocolFamily.RS_COMMON.readableFields.toPersistentSet(),
            writableSettings = persistentSetOf(
                DeviceSetting.GAME_MODE,
                DeviceSetting.TOUCH_BINDING,
                DeviceSetting.MULTIPOINT,
                DeviceSetting.TOUCH_ENABLED,
                DeviceSetting.EQ,
                DeviceSetting.AUDIO_MODE,
            ),
            equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi")),
            touchControl = TouchControlCapabilities(
                rsCommonTouchSlots,
                persistentSetOf("play_pause", "previous_track", "next_track", "volume_up", "volume_down"),
            ),
            audioControl = AudioControlCapabilities(persistentSetOf("aac_sbc", "lhdc", "lc3"), multipointCoupled = true),
        )
    },
)

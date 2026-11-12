package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.RsCommonInit
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

internal val sportFeel = ModelSpec(
    displayName = "SportFeel",
    shortLabel = "SportFeel",
    family = ProtocolFamily.RS_COMMON,
    advertisementPrefixes = listOf("0b0a0a0f"),
    nameBrand = "sportfeel",
    nameModelNumber = "sportfeel",
    nameBrandAliases = listOf("sportfree"),
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931",
    writeIntervalMillis = 100,
    advertisedClassicAddress = { rsCommonClassicAddress(it, 10) },
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
    equalizerPresetLabels = emptyMap(),
    rsCommonInit = RsCommonInit.DEFAULT,
    capabilities = {
        DeviceCapabilities(
            readableFields = ProtocolFamily.RS_COMMON.readableFields.toPersistentSet(),
            writableSettings = persistentSetOf(
                DeviceSetting.GAME_MODE,
                DeviceSetting.TOUCH_BINDING,
                DeviceSetting.MULTIPOINT,
                DeviceSetting.TOUCH_ENABLED,
                DeviceSetting.PROMPT_VOLUME,
                DeviceSetting.SPATIAL_AUDIO,
            ),
            touchControl = TouchControlCapabilities(
                rsCommonTouchSlots,
                persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down"),
            ),
            promptVolumeLevels = (1..5).toPersistentSet(),
        )
    },
)

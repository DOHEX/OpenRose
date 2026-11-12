package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val jasmineCapabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.RS_COMMON_V2.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.GAME_MODE,
        DeviceSetting.MULTIPOINT,
        DeviceSetting.TOUCH_ENABLED,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.PROMPT_LANGUAGE,
        DeviceSetting.PROMPT_VOLUME,
    ),
    touchControl = TouchControlCapabilities(
        earbudTouchSlots,
        persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "game_mode", "voice_assistant"),
    ),
    promptLanguages = persistentSetOf("chinese", "english"),
    // RoseLink's Jasmine panel disables the off option and sets no firmware gate.
    promptVolumeLevels = (1..5).toPersistentSet(),
)

internal val jasmine = ModelSpec(
    displayName = "Jasmine", shortLabel = "Jasmine", family = ProtocolFamily.RS_COMMON_V2,
    advertisementPrefixes = listOf("0b0a0a11"), nameBrand = "jasmine", nameModelNumber = "jasmine",
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931", writeIntervalMillis = 100, advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.EARBUDS_ONLY, equalizerPresetLabels = emptyMap(),
    capabilities = { jasmineCapabilities },
)

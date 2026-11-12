package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val swift2Capabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.RS_COMMON_V2.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.GAME_MODE,
        DeviceSetting.MULTIPOINT,
        DeviceSetting.TOUCH_ENABLED,
        DeviceSetting.PROMPT_VOLUME,
    ),
    // RoseLink's Swift 2 panel disables the off option and sets no firmware gate.
    promptVolumeLevels = (1..5).toPersistentSet(),
)

internal val swift2 = ModelSpec(
    displayName = "SWIFT 2", shortLabel = "SWIFT 2", family = ProtocolFamily.RS_COMMON_V2,
    advertisementPrefixes = listOf("5999064c"), nameBrand = "swift", nameModelNumber = "swift2", nameRequiresModel = true,
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931", writeIntervalMillis = 100, advertisedClassicAddress = null,
    batteryLayout = BatteryLayout.SINGLE_UNIT, equalizerPresetLabels = emptyMap(),
    capabilities = { swift2Capabilities },
)

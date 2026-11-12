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

internal val swift = ModelSpec(
    displayName = "SWIFT",
    shortLabel = "SWIFT",
    family = ProtocolFamily.RS_COMMON,
    advertisementPrefixes = listOf("0b0b0a0b"),
    nameBrand = "swift",
    nameModelNumber = "swift",
    nameBrandAliases = listOf(),
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = { rsCommonClassicAddress(it, 10) },
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    equalizerPresetLabels = emptyMap(),
    rsCommonInit = RsCommonInit.DEFAULT,
    capabilities = {
        DeviceCapabilities(
            readableFields = ProtocolFamily.RS_COMMON.readableFields.toPersistentSet(),
            writableSettings = persistentSetOf(
                DeviceSetting.GAME_MODE,
                DeviceSetting.TOUCH_BINDING,
            ),
            touchControl = TouchControlCapabilities(
                rsCommonTouchSlots,
                persistentSetOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down"),
            ),
        )
    },
)

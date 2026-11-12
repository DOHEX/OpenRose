package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

private val agateCapabilities = DeviceCapabilities(
    readableFields = ProtocolFamily.F8.readableFields.toPersistentSet(),
    writableSettings = persistentSetOf(
        DeviceSetting.EQ,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.GAME_MODE,
    ),
    equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi")),
    touchControl = TouchControlCapabilities(f8ThreeGestureSlots, f8Actions),
)

internal val agate = ModelSpec(
    displayName = "AGATE",
    shortLabel = "AGATE",
    family = ProtocolFamily.F8,
    advertisementPrefixes = listOf("18840505"),
    nameBrand = "agate",
    nameModelNumber = "agate",
    nameRequiresModel = true,
    gatt = GattProfile(
        "0000fdb3-0000-1000-8000-00805f9b34fb",
        "0000ff16-0000-1000-8000-00805f9b34fb",
        "0000ff17-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = null,
    writeIntervalMillis = 100,
    advertisedClassicAddress = ::f8Address,
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
    equalizerPresetLabels = mapOf("pop" to "Rock", "hifi" to "HIFI"),
    capabilities = { agateCapabilities },
    f8ExtendedInit = false,
    f8NoiseNormalWire = 3,
    f8CollapsedEqRead = true,
)

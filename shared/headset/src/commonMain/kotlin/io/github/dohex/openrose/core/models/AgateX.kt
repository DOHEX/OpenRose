package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import kotlinx.collections.immutable.persistentSetOf

private val agateXCapabilities = DeviceCapabilities(
    readableFields = f8BasicReadings,
    writableSettings = persistentSetOf(
        DeviceSetting.EQ,
        DeviceSetting.TOUCH_BINDING,
    ),
    equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi")),
    touchControl = TouchControlCapabilities(f8ThreeGestureSlots, f8ActionsWithoutNoise),
)

internal val agateX = ModelSpec(
    displayName = "AGATE X",
    shortLabel = "AGATE X",
    protocol = ModelProtocol.F8(
        extendedInit = false,
        noiseNormalWire = 3,
        eqAtTwo = "pop",
    ),
    advertisementPrefixes = listOf("18840506"),
    nameBrand = "agatex",
    nameModelNumber = "agatex",
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
    equalizerPresetLabels = mapOf("pop" to "Rock", "hifi" to "POP"),
    capabilities = { agateXCapabilities },
)

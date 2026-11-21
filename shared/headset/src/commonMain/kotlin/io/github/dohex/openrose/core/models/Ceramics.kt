package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import kotlinx.collections.immutable.persistentSetOf

private val ceramicsCapabilities = DeviceCapabilities(
    readableFields = f8BasicReadings,
    writableSettings = persistentSetOf(
        DeviceSetting.EQ,
        DeviceSetting.TOUCH_BINDING,
        DeviceSetting.GAME_MODE,
    ),
    equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi")),
    touchControl = TouchControlCapabilities(f8ThreeGestureSlots, f8Actions),
)

internal val ceramics = ModelSpec(
    displayName = "CERAMICS",
    shortLabel = "CERAMICS",
    protocol = ModelProtocol.F8(
        extendedInit = false,
        noiseNormalWire = 3,
        eqAtTwo = null,
    ),
    advertisementPrefixes = listOf("18840100", "18840103"),
    nameBrand = "ceramics",
    nameModelNumber = "ceramics",
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
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
    capabilities = { ceramicsCapabilities },
)

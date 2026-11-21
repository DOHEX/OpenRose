package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.RsCommonInit

internal val cambrianS = ModelSpec(
    displayName = "Cambrian S",
    shortLabel = "Cambrian S",
    family = ProtocolFamily.RS_COMMON,
    advertisementPrefixes = listOf("0b0c2b46"),
    nameBrand = "cambrian",
    nameModelNumber = "cambrian s",
    nameRequiresModel = true,
    gatt = GattProfile(
        "000000fe-0000-1000-8000-00805f9b34fb",
        "000000f1-0000-1000-8000-00805f9b34fb",
        "000000f2-0000-1000-8000-00805f9b34fb",
    ),
    sppControlUuid = "0cf12d31-fac3-4553-bd80-d6832e7b3931",
    writeIntervalMillis = 100,
    advertisedClassicAddress = { rsCommonClassicAddress(it, 10) },
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
    rsCommonInit = RsCommonInit.CAMBRIAN,
    capabilities = { cambrianControls },
)

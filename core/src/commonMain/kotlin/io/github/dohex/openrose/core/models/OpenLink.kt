package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.RsCommonInit

internal val openLink = ModelSpec(
    displayName = "OpenLink",
    shortLabel = "OpenLink",
    family = ProtocolFamily.RS_COMMON,
    advertisementPrefixes = listOf("0b0a5b9d"),
    nameBrand = "openlink",
    nameModelNumber = "openlink",
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
    capabilities = { rsOpenTouchControls },
)

package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.BatteryLayout

internal val magician = rsV2Model(
    displayName = "Magician",
    prefix = "0b0e0a0d",
    batteryLayout = BatteryLayout.SINGLE_UNIT,
) {
    rsV2Capabilities(
        layout = BatteryLayout.SINGLE_UNIT,
        promptVolume = 1..5,
        audioModes = setOf("aac_sbc", "ldac", "aptx", "aptx_hd"),
        promptLanguages = true,
        findEvents = true,
    )
}

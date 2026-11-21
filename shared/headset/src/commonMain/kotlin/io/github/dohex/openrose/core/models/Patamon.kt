package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.BatteryLayout

internal val patamon = rsV2Model(
    infoIds = "0d0c0e3201072f2e",
    displayName = "Patamon",
    prefix = "0b0ae916",
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_ONLY,
        promptVolume = 1..5,
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        findEvents = true,
    )
}

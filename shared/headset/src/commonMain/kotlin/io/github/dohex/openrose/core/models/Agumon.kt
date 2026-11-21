package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.BatteryLayout

internal val agumon = rsV2Model(
    infoIds = "0d0c0e3201072f2e",
    displayName = "Agumon",
    prefix = "0b0a34fd",
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

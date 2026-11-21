package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val budsFeelAeNeo = rsV2Model(
    infoIds = "0d0c2a45460e3201072f2e",
    displayName = "BudsFeel AE Neo",
    prefix = "45d789b0",
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
    nameBrand = "budsfeel",
    nameModelNumber = "aeneo",
    nameRequiresModel = true,
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_ONLY,
        promptVolume = 0..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock"), rsV2Curve),
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        gain = true,
        protection = true,
        findEvents = true,
    )
}

package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val coral = rsV2Model(
    infoIds = "0d0c092a0e322b01072f2e",
    displayName = "Coral",
    prefix = "0b0e0a0e",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        noise = NoiseControlCapabilities(persistentSetOf("anc", "normal"), persistentSetOf()),
        touch = rsV2Touch(noiseCycle = true),
        audioModes = setOf("aac_sbc", "ldac", "lhdc", "lc3"),
        touchEnabled = true,
        bassBoostLevels = 0..2,
        findEvents = true,
    )
}

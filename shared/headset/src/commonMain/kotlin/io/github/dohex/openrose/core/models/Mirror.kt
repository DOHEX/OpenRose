package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val mirror = rsV2Model(
    infoIds = "0d0c2a0e3201072f2e",
    displayName = "Mirror",
    prefix = "0b0a0a13",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        findEvents = true,
    )
}

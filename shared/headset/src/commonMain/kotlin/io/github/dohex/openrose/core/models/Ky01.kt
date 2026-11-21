package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val ky01 = rsV2Model(
    infoIds = "0d0c2a0e320107312f2e",
    displayName = "KY01",
    prefix = "0a0b0a0d",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        touch = rsV2Touch(noiseCycle = true),
        touchEnabled = true,
        promptLanguages = true,
        findEvents = true,
    )
}

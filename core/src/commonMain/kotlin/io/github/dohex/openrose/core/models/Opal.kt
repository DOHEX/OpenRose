package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val opal = rsV2Model(
    infoIds = "0d0c092a0e3201072f2e",
    displayName = "Opal",
    prefix = "0b0a0a12",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        noise = NoiseControlCapabilities(modes = persistentSetOf("normal", "transparency", "anc"), cycleModes = persistentSetOf()),
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        touch = rsV2Touch(noiseCycle = true),
        touchEnabled = true,
        findEvents = true,
    )
}

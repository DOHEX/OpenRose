package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val hyacinth = rsV2Model(
    infoIds = "0d0c2a0e322b2f0107312e",
    displayName = "Hyacinth",
    prefix = "0b120a0b",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        audioModes = setOf("aac_sbc", "ldac"),
        promptLanguages = true,
        findEvents = true,
    )
}

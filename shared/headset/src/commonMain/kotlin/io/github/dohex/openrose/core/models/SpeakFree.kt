package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val speakFree = rsV2Model(
    infoIds = "0d0c2a0e322b2f312e",
    displayName = "SpeakFeel (speakfree)",
    prefix = "0b0f0a0b",
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    nameBrand = "speakfeel",
    nameModelNumber = "speakfeel",
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.SINGLE_UNIT,
        promptVolume = 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        audioModes = setOf("aac_sbc", "ldac", "aptx", "aptx_hd", "aptx_adaptive"),
        promptLanguages = true,
        findEvents = true,
    )
}

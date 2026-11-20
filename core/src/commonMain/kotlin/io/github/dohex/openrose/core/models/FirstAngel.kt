package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.firmwareComparisonInteger
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val firstAngel = rsV2Model(
    infoIds = "0d0c2a0e3201072f3f2e",
    displayName = "FIRST ANGEL",
    prefix = "0b0e64be",
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    refreshEqualizerAfterGameMode = true,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "game_1" to "战术调律"),
    gameEqualizerPresets = setOf("game_1"),
) { firmware ->
    rsV2Capabilities(
        layout = BatteryLayout.SINGLE_UNIT,
        promptVolume = if ((firmwareComparisonInteger(firmware) ?: 0) >= 510) 0..5 else 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi"), rsV2Curve),
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        findEvents = true,
    )
}

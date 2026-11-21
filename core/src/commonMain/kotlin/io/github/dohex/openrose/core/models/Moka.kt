package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val moka = rsV2Model(
    infoIds = "0d0c2a0e322b2e",
    displayName = "MOKA",
    prefix = "68f5b8eb",
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    refreshEqualizerAfterGameMode = true,
    equalizerPresetLabels = mapOf("pop" to "POP", "hifi" to "HIFI", "rock" to "ROCK"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.SINGLE_UNIT,
        promptVolume = 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock"), rsV2Curve),
        audioModes = setOf("aac_sbc", "ldac"),
    )
}

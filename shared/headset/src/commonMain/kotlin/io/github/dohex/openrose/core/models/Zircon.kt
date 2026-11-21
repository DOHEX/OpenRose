package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val zircon = rsV2Model(
    infoIds = "0d0c092c2d2a0e322b2f010731082e36",
    displayName = "Zircon",
    prefix = "0b110a0b",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        noise = NoiseControlCapabilities(
            modes = basicNoiseModes.adding("deep"),
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        touch = rsV2Touch(noiseCycle = true),
        audioModes = setOf("aac_sbc", "ldac", "lhdc", "lc3"),
        touchEnabled = true,
        earDetection = true,
        promptLanguages = true,
        findEvents = true,
    )
}

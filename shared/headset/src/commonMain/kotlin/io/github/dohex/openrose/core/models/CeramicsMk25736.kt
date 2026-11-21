package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val ceramicsMk25736 = rsV2Model(
    infoIds = "0d0c092c2d2a0e322f01072e36",
    displayName = "CERAMICS-MK2 5736",
    prefix = "45d7b286",
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
    nameBrand = "ceramics",
    nameModelNumber = "mk2",
    nameRequiresModel = true,
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_ONLY,
        promptVolume = 0..5,
        noise = NoiseControlCapabilities(
            modes = basicNoiseModes,
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            persistentSetOf("pop", "hifi", "rock"),
            custom = rsV2Curve,
        ),
        touch = rsV2Touch(noiseCycle = true),
        touchEnabled = true,
        findEvents = true,
    )
}

package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val ceramicsUHsc2710 = rsV2Model(
    infoIds = "0d0c092c2d2a450e322b2f01072e36",
    displayName = "CERAMICS-U HSC-2710",
    prefix = "0b137957",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameBrand = "ceramics",
    nameModelNumber = "ceramicsu",
    nameRequiresModel = true,
    equalizerPresetLabels = mapOf("light" to "经典调音"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 0..5,
        noise = NoiseControlCapabilities(
            modes = basicNoiseModes.adding("deep"),
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            persistentSetOf("pop", "hifi", "rock", "light"),
            custom = rsV2Curve,
        ),
        touch = rsV2Touch(noiseCycle = true),
        audioModes = setOf("aac_sbc", "ldac", "lhdc"),
        touchEnabled = true,
        gain = true,
        findEvents = true,
    )
}

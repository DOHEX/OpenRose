package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.firmwareAtLeast
import io.github.dohex.openrose.core.promptVolumeLevels
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val ceramicsU = rsV2Model(
    infoIds = "0d0c092c2d2a450e322b2f0107312e36",
    displayName = "CERAMICS-U",
    prefix = "0b0d0a0b",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameBrand = "ceramics",
    nameModelNumber = "ceramicsu",
    nameRequiresModel = true,
    equalizerPresetLabels = mapOf("light" to "经典调音"),
) { firmware ->
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = promptVolumeLevels(firmware, offSince = 33),
        noise = NoiseControlCapabilities(
            modes = basicNoiseModes.addingAll(listOf("auto", "deep")),
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            persistentSetOf("pop", "hifi", "rock", "light"),
            custom = if (firmwareAtLeast(firmware, 33) == true) rsV2Curve else null,
        ),
        touch = rsV2Touch(noiseCycle = true),
        audioModes = setOf("aac_sbc", "ldac", "lhdc"),
        touchEnabled = true,
        promptLanguages = true,
        gain = firmwareAtLeast(firmware, 33) == true,
        findEvents = true,
    )
}

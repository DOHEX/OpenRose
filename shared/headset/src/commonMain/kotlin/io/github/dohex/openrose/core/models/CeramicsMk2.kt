package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.firmwareAtLeast
import io.github.dohex.openrose.core.promptVolumeLevels
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val ceramicsMk2 = rsV2Model(
    infoIds = "0d0c092c2d2a0e322b2f0107312e36",
    displayName = "CERAMICS-MK2",
    prefix = "0b0e0a0c",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameBrand = "ceramics",
    nameModelNumber = "mk2",
    nameRequiresModel = true,
) { firmware ->
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = promptVolumeLevels(firmware, offSince = 153),
        noise = NoiseControlCapabilities(
            modes = basicNoiseModes,
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            persistentSetOf("pop", "hifi", "rock"),
            custom = if (firmwareAtLeast(firmware, 154) == true) rsV2Curve else null,
        ),
        touch = rsV2Touch(noiseCycle = true),
        audioModes = setOf("aac_sbc", "ldac"),
        touchEnabled = true,
        promptLanguages = true,
        findEvents = true,
    )
}

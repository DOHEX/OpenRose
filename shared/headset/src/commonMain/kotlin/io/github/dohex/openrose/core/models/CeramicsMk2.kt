package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.firmwareComparisonInteger
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
    val version = firmwareComparisonInteger(firmware) ?: 0
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = if (version >= 153) 0..5 else 1..5,
        noise = NoiseControlCapabilities(
            modes = basicNoiseModes,
            cycleModes = basicNoiseModes,
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("transparency")),
        ),
        equalizer = EqualizerCapabilities(
            persistentSetOf("pop", "hifi", "rock"),
            custom = if (version >= 154) rsV2Curve else null,
        ),
        touch = rsV2Touch(noiseCycle = true),
        audioModes = setOf("aac_sbc", "ldac"),
        touchEnabled = true,
        promptLanguages = true,
        findEvents = true,
    )
}

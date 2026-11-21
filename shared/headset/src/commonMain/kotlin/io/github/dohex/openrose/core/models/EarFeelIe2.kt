package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val earFeelIe2 = rsV2Model(
    infoIds = "0d0c092a0e322b01072f2e36",
    displayName = "EarFeel iE2",
    prefix = "0b154572",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameRequiresModel = true,
    nameBrand = "earfeel",
    nameModelNumber = "ie2",
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        noise = NoiseControlCapabilities(basicNoiseModes, basicNoiseModes),
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock", "light"), rsV2Curve),
        touch = rsV2Touch(noiseCycle = true),
        touchEnabled = true,
        findEvents = true,
        audioModes = setOf("aac_sbc", "ldac"),
    )
}

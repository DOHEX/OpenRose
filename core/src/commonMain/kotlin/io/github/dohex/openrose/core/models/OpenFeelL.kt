package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val openFeelL = rsV2Model(
    infoIds = "0d0c2a45460e322b01072f2e",
    displayName = "OpenFeel L",
    prefix = "45d7cbed",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameBrand = "openfeel",
    nameModelNumber = "openfeell",
    nameRequiresModel = true,
    equalizerPresetLabels = mapOf("pop" to "BASS", "hifi" to "BALANCE"),
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 0..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi"), rsV2Curve),
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        audioModes = setOf("aac_sbc", "ldac"),
        gain = true,
        protection = true,
        findEvents = true,
    )
}

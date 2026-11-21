package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val sportFeel8912f = rsV2Model(
    infoIds = "0d0c2a0e322b2f01072e",
    displayName = "SportFeel 8912F",
    prefix = "5d2f61f0",
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameBrand = "sportfeel",
    nameModelNumber = "sportfeel",
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 0..5,
        equalizer = EqualizerCapabilities(persistentSetOf("hifi")),
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        audioModes = setOf("aac_sbc", "ldac"),
        findEvents = true,
    )
}

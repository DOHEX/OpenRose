package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.BatteryLayout

internal val speakFeel2 = rsV2Model(
    infoIds = "0d0c2a45460e49322b012f3f2e",
    displayName = "SPEAKFEEL (speakfeel2)",
    prefix = "0b0e3ce6",
    batteryLayout = BatteryLayout.SINGLE_UNIT,
    nameBrand = "speakfeel",
    nameModelNumber = "speakfeel",
) {
    rsV2Capabilities(
        layout = BatteryLayout.SINGLE_UNIT,
        promptVolume = 0..5,
        touch = rsV2Touch(noiseCycle = false),
        touchEnabled = true,
        audioModes = setOf("aac_sbc", "ldac", "lhdc"),
        gain = true,
        protection = true,
        bassBoostLevels = 0..1,
        independentMic = true,
        findEvents = true,
    )
}

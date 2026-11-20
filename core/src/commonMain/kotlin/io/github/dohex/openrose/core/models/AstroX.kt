package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.firmwareComparisonInteger
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

internal val astroX = rsV2Model(
    infoIds = "0d0c383d2a45460e322b01073b3c2f082e3a",
    displayName = "Astro X",
    synchronizeClockOnConnect = true,
    prefix = "0b0e0a0b",
    spatialNeedsEarAdaptive = true,
    batteryLayout = BatteryLayout.EARBUDS_AND_CASE,
    nameBrand = "astro", nameModelNumber = "astrox", nameRequiresModel = true,
    equalizerPresetLabels = mapOf("pop" to "BASS", "hifi" to "BALANCE", "rock" to "BASS+"),
) { firmware ->
    val hasGainAndProtection = (firmwareComparisonInteger(firmware) ?: 0) >= 152
    val touch = rsV2Touch(noiseCycle = false)
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_AND_CASE,
        promptVolume = 1..5,
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock")),
        touch = touch,
        tap = touch.copy(
            slots = persistentSetOf(
                TouchSlot("left", "double"),
                TouchSlot("left", "triple"),
                TouchSlot("right", "double"),
                TouchSlot("right", "triple"),
            ),
        ),
        touchEnabled = true,
        audioModes = setOf("aac_sbc", "ldac", "lhdc"),
        earDetection = true,
        gain = hasGainAndProtection,
        protection = hasGainAndProtection,
        findEvents = true,
        spatialModes = setOf("off", "head_tracking", "fixed"),
        spatialContents = setOf("tv", "music", "movie", "game"),
        earAdaptive = true,
    )
}

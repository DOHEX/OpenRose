package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import kotlinx.collections.immutable.persistentSetOf

private val ieNeoNoiseModes = persistentSetOf("anc", "normal", "transparency")

internal val earFeelIeNeo = rsV2Model(
    infoIds = "0d0c092a45460e3201072f2e36",
    displayName = "EarFeel iE Neo",
    prefix = "45d7aa12",
    batteryLayout = BatteryLayout.EARBUDS_ONLY,
    nameRequiresModel = true,
    nameBrand = "earfeel",
    nameModelNumber = "ieneo",
) {
    rsV2Capabilities(
        layout = BatteryLayout.EARBUDS_ONLY,
        promptVolume = 0..5,
        noise = NoiseControlCapabilities(ieNeoNoiseModes, ieNeoNoiseModes),
        equalizer = EqualizerCapabilities(persistentSetOf("pop", "hifi", "rock"), rsV2Curve),
        touch = rsV2Touch(noiseCycle = true),
        touchEnabled = true,
        gain = true,
        protection = true,
        findEvents = true,
    )
}

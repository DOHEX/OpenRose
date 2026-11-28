package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import uniffi.openrose_headset.AudioControlCapabilities as RustAudioControl
import uniffi.openrose_headset.CustomEqualizerCapabilities as RustCustomEqualizer
import uniffi.openrose_headset.EqualizerCapabilities as RustEqualizer
import uniffi.openrose_headset.HeadsetCapabilities as RustCapabilities
import uniffi.openrose_headset.NoiseControlCapabilities as RustNoiseControl
import uniffi.openrose_headset.NoiseLevelCapabilities as RustNoiseLevel
import uniffi.openrose_headset.TouchControlCapabilities as RustTouchControl
import uniffi.openrose_headset.TouchSlot as RustTouchSlot

internal fun RustCapabilities.toKotlin(): DeviceCapabilities = DeviceCapabilities(
    readableFields = readableFields.map { it.toKotlin() }.toPersistentSet(),
    writableSettings = writableSettings.map { it.toKotlin() }.toPersistentSet(),
    noiseControl = noiseControl?.toKotlin(),
    equalizer = equalizer?.toKotlin(),
    touchControl = touchControl?.toKotlin(),
    tapControl = tapControl?.toKotlin(),
    audioControl = audioControl?.toKotlin(),
    promptVolumeLevels = promptVolumeLevels.toPersistentSet(),
    gainLevels = gainLevels.toPersistentSet(),
    hearingProtectionLevels = hearingProtectionLevels.toPersistentSet(),
    bassBoostLevels = bassBoostLevels.toPersistentSet(),
    promptLanguages = promptLanguages.toPersistentSet(),
    spatialModes = spatialModes.toPersistentSet(),
    spatialContents = spatialContents.toPersistentSet(),
)

private fun RustNoiseControl.toKotlin(): NoiseControlCapabilities = NoiseControlCapabilities(
    modes = modes.toPersistentSet(),
    cycleModes = cycleModes.toPersistentSet(),
    ancLevel = ancLevel?.toKotlin(),
    transparencyLevel = transparencyLevel?.toKotlin(),
    minimumCycleSelections = minimumCycleSelections.toInt(),
)

private fun RustNoiseLevel.toKotlin() = NoiseLevelCapabilities(levels.toPersistentSet(), visibleModes.toPersistentSet())

private fun RustEqualizer.toKotlin(): EqualizerCapabilities = EqualizerCapabilities(presets.toPersistentSet(), custom?.toKotlin())

private fun RustCustomEqualizer.toKotlin() = CustomEqualizerCapabilities(bandsHz.toPersistentList(), minGain, maxGain)

private fun RustTouchControl.toKotlin(): TouchControlCapabilities = TouchControlCapabilities(
    slots = slots.map { it.toKotlin() }.toPersistentSet(),
    actions = actions.toPersistentSet(),
    slotLabels = slotLabels.entries.associate { (slot, label) -> slot.toKotlin() to label }.toPersistentMap(),
)

private fun RustTouchSlot.toKotlin(): TouchSlot = TouchSlot(side, gesture)

private fun RustAudioControl.toKotlin(): AudioControlCapabilities = AudioControlCapabilities(modes.toPersistentSet(), multipointCoupled)

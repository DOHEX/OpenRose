package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.ffi.EnumNames
import uniffi.openrose_headset.HeadsetCommand as RustHeadsetCommand
import uniffi.openrose_headset.NoiseMode as RustNoiseMode

private val rustNoiseModes = EnumNames(RustNoiseMode.entries)
private val kotlinNoiseModes = EnumNames(NoiseMode.entries)

fun NoiseMode.toRust(): RustNoiseMode = rustNoiseModes.of(this)

fun RustNoiseMode.toKotlin(): NoiseMode = kotlinNoiseModes.of(this)

@Suppress("CyclomaticComplexMethod")
fun HeadsetCommand.toRust(): RustHeadsetCommand = when (this) {
    is HeadsetCommand.NoiseMode -> RustHeadsetCommand.NoiseMode(mode.toRust())
    is HeadsetCommand.AncLevel -> RustHeadsetCommand.AncLevel(level)
    is HeadsetCommand.TransparencyLevel -> RustHeadsetCommand.TransparencyLevel(level)
    is HeadsetCommand.NoiseCycle -> RustHeadsetCommand.NoiseCycle(flags)
    is HeadsetCommand.EqualizerPreset -> RustHeadsetCommand.EqualizerPreset(preset)
    is HeadsetCommand.CustomEqualizer -> RustHeadsetCommand.CustomEqualizer(gains)
    is HeadsetCommand.TouchBinding -> RustHeadsetCommand.TouchBinding(side, gesture, action)
    is HeadsetCommand.TapBinding -> RustHeadsetCommand.TapBinding(side, gesture, action)
    is HeadsetCommand.PromptVolume -> RustHeadsetCommand.PromptVolume(level)
    is HeadsetCommand.Gain -> RustHeadsetCommand.Gain(level)
    is HeadsetCommand.HearingProtection -> RustHeadsetCommand.HearingProtection(level)
    is HeadsetCommand.BassBoost -> RustHeadsetCommand.BassBoost(level)
    is HeadsetCommand.GameMode -> RustHeadsetCommand.GameMode(enabled)
    is HeadsetCommand.Multipoint -> RustHeadsetCommand.Multipoint(enabled)
    is HeadsetCommand.TouchEnabled -> RustHeadsetCommand.TouchEnabled(enabled)
    is HeadsetCommand.EarDetection -> RustHeadsetCommand.EarDetection(enabled)
    is HeadsetCommand.TapEnabled -> RustHeadsetCommand.TapEnabled(enabled)
    is HeadsetCommand.SpatialAudio -> RustHeadsetCommand.SpatialAudio(enabled)
    is HeadsetCommand.EarAdaptive -> RustHeadsetCommand.EarAdaptive(enabled)
    is HeadsetCommand.IndependentMicActivation -> RustHeadsetCommand.IndependentMicActivation(enabled)
    is HeadsetCommand.IndependentMicMode -> RustHeadsetCommand.IndependentMicMode(enabled)
    is HeadsetCommand.AudioMode -> RustHeadsetCommand.AudioMode(mode)
    is HeadsetCommand.PromptLanguage -> RustHeadsetCommand.PromptLanguage(language)
    is HeadsetCommand.SpatialMode -> RustHeadsetCommand.SpatialMode(mode)
    is HeadsetCommand.SpatialContent -> RustHeadsetCommand.SpatialContent(content)
}

@Suppress("CyclomaticComplexMethod")
fun RustHeadsetCommand.toKotlin(): HeadsetCommand = when (this) {
    is RustHeadsetCommand.NoiseMode -> HeadsetCommand.NoiseMode(v1.toKotlin())
    is RustHeadsetCommand.AncLevel -> HeadsetCommand.AncLevel(v1)
    is RustHeadsetCommand.TransparencyLevel -> HeadsetCommand.TransparencyLevel(v1)
    is RustHeadsetCommand.NoiseCycle -> HeadsetCommand.NoiseCycle(v1)
    is RustHeadsetCommand.EqualizerPreset -> HeadsetCommand.EqualizerPreset(v1)
    is RustHeadsetCommand.CustomEqualizer -> HeadsetCommand.CustomEqualizer(v1)
    is RustHeadsetCommand.TouchBinding -> HeadsetCommand.TouchBinding(side, gesture, action)
    is RustHeadsetCommand.TapBinding -> HeadsetCommand.TapBinding(side, gesture, action)
    is RustHeadsetCommand.PromptVolume -> HeadsetCommand.PromptVolume(v1)
    is RustHeadsetCommand.Gain -> HeadsetCommand.Gain(v1)
    is RustHeadsetCommand.HearingProtection -> HeadsetCommand.HearingProtection(v1)
    is RustHeadsetCommand.BassBoost -> HeadsetCommand.BassBoost(v1)
    is RustHeadsetCommand.GameMode -> HeadsetCommand.GameMode(v1)
    is RustHeadsetCommand.Multipoint -> HeadsetCommand.Multipoint(v1)
    is RustHeadsetCommand.TouchEnabled -> HeadsetCommand.TouchEnabled(v1)
    is RustHeadsetCommand.EarDetection -> HeadsetCommand.EarDetection(v1)
    is RustHeadsetCommand.TapEnabled -> HeadsetCommand.TapEnabled(v1)
    is RustHeadsetCommand.SpatialAudio -> HeadsetCommand.SpatialAudio(v1)
    is RustHeadsetCommand.EarAdaptive -> HeadsetCommand.EarAdaptive(v1)
    is RustHeadsetCommand.IndependentMicActivation -> HeadsetCommand.IndependentMicActivation(v1)
    is RustHeadsetCommand.IndependentMicMode -> HeadsetCommand.IndependentMicMode(v1)
    is RustHeadsetCommand.AudioMode -> HeadsetCommand.AudioMode(v1)
    is RustHeadsetCommand.PromptLanguage -> HeadsetCommand.PromptLanguage(v1)
    is RustHeadsetCommand.SpatialMode -> HeadsetCommand.SpatialMode(v1)
    is RustHeadsetCommand.SpatialContent -> HeadsetCommand.SpatialContent(v1)
}

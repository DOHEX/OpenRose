package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import io.github.dohex.openrose.core.NoiseMode as HeadsetNoiseMode

/** A write intent. The session validates model options and waits for a fresh device confirmation. */
sealed class HeadsetCommand(val setting: DeviceSetting, val value: FieldValue) {
    internal open fun checkOptions(device: DeviceState): ControlError? = null

    protected fun invalidUnless(condition: Boolean): ControlError? {
        if (condition) return null
        return ControlError(ControlErrorCode.INVALID_ARGUMENT, "${setting.name} 参数无效")
    }

    protected fun checkNoiseLevel(level: Int, capability: NoiseLevelCapabilities?): ControlError? {
        if (capability == null) return unsupported("该型号不支持此档位设置")
        return invalidUnless(level in capability.levels)
    }

    data class NoiseMode(val mode: HeadsetNoiseMode) : HeadsetCommand(DeviceSetting.NOISE_MODE, FieldValue.Text(mode.id)) {
        override fun checkOptions(device: DeviceState): ControlError? {
            if (mode.id in device.capabilities.noiseControl?.modes.orEmpty()) return null
            return unsupported("该型号不支持 ${mode.id} 模式")
        }
    }

    data class AncLevel(val level: Int) : HeadsetCommand(DeviceSetting.ANC_LEVEL, FieldValue.Number(level)) {
        override fun checkOptions(device: DeviceState) = checkNoiseLevel(level, device.capabilities.noiseControl?.ancLevel)
    }

    data class TransparencyLevel(val level: Int) : HeadsetCommand(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(level)) {
        override fun checkOptions(device: DeviceState) = checkNoiseLevel(level, device.capabilities.noiseControl?.transparencyLevel)
    }

    data class NoiseCycle(
        val flags: PersistentMap<String, Boolean>,
    ) : HeadsetCommand(DeviceSetting.NOISE_CYCLE, FieldValue.NoiseCycle(flags)) {
        constructor(flags: Map<String, Boolean>) : this(flags.toPersistentMap())
        override fun checkOptions(device: DeviceState): ControlError? {
            val noise = device.capabilities.noiseControl ?: return unsupported("该型号不支持降噪轮换")
            return invalidUnless(flags.keys == noise.cycleModes && flags.values.count { it } >= noise.minimumCycleSelections)
        }
    }

    data class EqualizerPreset(val preset: String) : HeadsetCommand(DeviceSetting.EQ, FieldValue.Text(preset)) {
        override fun checkOptions(device: DeviceState): ControlError? {
            if (preset in device.capabilities.equalizer?.presets.orEmpty()) return null
            return unsupported("该型号与固件不支持 $preset EQ")
        }
    }

    data class CustomEqualizer(
        val gains: PersistentList<Int>,
    ) : HeadsetCommand(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(gains)) {
        constructor(gains: List<Int>) : this(gains.toPersistentList())
        override fun checkOptions(device: DeviceState): ControlError? {
            val custom = device.capabilities.equalizer?.custom ?: return unsupported("该型号不支持自定义均衡器")
            return invalidUnless(gains.size == custom.bandsHz.size && gains.all { it in custom.minGain..custom.maxGain })
        }
    }

    data class TouchBinding(
        val side: String,
        val gesture: String,
        val action: String,
    ) : HeadsetCommand(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment(side, gesture, action)) {
        override fun checkOptions(device: DeviceState) = checkBinding(device.capabilities.touchControl, side, gesture, action)
    }

    data class TapBinding(
        val side: String,
        val gesture: String,
        val action: String,
    ) : HeadsetCommand(DeviceSetting.TAP_BINDING, FieldValue.TouchAssignment(side, gesture, action)) {
        override fun checkOptions(device: DeviceState) = checkBinding(device.capabilities.tapControl, side, gesture, action)
    }

    data class PromptVolume(val level: Int) : HeadsetCommand(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level)) {
        override fun checkOptions(device: DeviceState): ControlError? = when (level) {
            in device.capabilities.promptVolumeLevels -> null
            in device.profile.family.promptVolumeLevels -> unsupported(if (level == 0) "当前固件不支持关闭提示音" else "当前固件不支持此提示音档位")
            else -> invalidUnless(false)
        }
    }

    data class Gain(val level: Int) : HeadsetCommand(DeviceSetting.GAIN, FieldValue.Number(level)) {
        override fun checkOptions(device: DeviceState): ControlError? = invalidUnless(level in device.capabilities.gainLevels)
    }

    data class HearingProtection(val level: Int) : HeadsetCommand(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(level)) {
        override fun checkOptions(device: DeviceState): ControlError? = invalidUnless(level in device.capabilities.hearingProtectionLevels)

        companion object {
            /** The volume ceiling in dB a protection level stands for; level 0 is off and levels outside 1..6 have none. */
            fun thresholdDb(level: Int): Int? = if (level in 1..6) 70 + level * 5 else null
        }
    }

    data class BassBoost(val level: Int) : HeadsetCommand(DeviceSetting.BASS_BOOST, FieldValue.Number(level)) {
        override fun checkOptions(device: DeviceState): ControlError? = invalidUnless(level in device.capabilities.bassBoostLevels)
    }

    data class GameMode(val enabled: Boolean) : HeadsetCommand(DeviceSetting.GAME_MODE, FieldValue.Toggle(enabled))

    data class Multipoint(val enabled: Boolean) : HeadsetCommand(DeviceSetting.MULTIPOINT, FieldValue.Toggle(enabled))

    data class TouchEnabled(val enabled: Boolean) : HeadsetCommand(DeviceSetting.TOUCH_ENABLED, FieldValue.Toggle(enabled))

    data class EarDetection(val enabled: Boolean) : HeadsetCommand(DeviceSetting.EAR_DETECTION, FieldValue.Toggle(enabled))

    data class TapEnabled(val enabled: Boolean) : HeadsetCommand(DeviceSetting.TAP_ENABLED, FieldValue.Toggle(enabled))

    data class SpatialAudio(val enabled: Boolean) : HeadsetCommand(DeviceSetting.SPATIAL_AUDIO, FieldValue.Toggle(enabled))

    data class EarAdaptive(val enabled: Boolean) : HeadsetCommand(DeviceSetting.EAR_ADAPTIVE, FieldValue.Toggle(enabled))

    data class IndependentMicActivation(
        val enabled: Boolean,
    ) : HeadsetCommand(DeviceSetting.INDEPENDENT_MIC_ACTIVATION, FieldValue.Toggle(enabled))

    data class IndependentMicMode(
        val enabled: Boolean,
    ) : HeadsetCommand(DeviceSetting.INDEPENDENT_MIC_MODE, FieldValue.Toggle(enabled))

    data class AudioMode(val mode: String) : HeadsetCommand(DeviceSetting.AUDIO_MODE, FieldValue.Text(mode)) {
        override fun checkOptions(device: DeviceState): ControlError? {
            if (mode in device.capabilities.audioControl?.modes.orEmpty()) return null
            return unsupported("该型号不支持 $mode 音频配置")
        }
    }

    data class PromptLanguage(val language: String) : HeadsetCommand(DeviceSetting.PROMPT_LANGUAGE, FieldValue.Text(language)) {
        override fun checkOptions(device: DeviceState): ControlError? = invalidUnless(language in device.capabilities.promptLanguages)
    }

    data class SpatialMode(val mode: String) : HeadsetCommand(DeviceSetting.SPATIAL_MODE, FieldValue.Text(mode)) {
        override fun checkOptions(device: DeviceState): ControlError? = invalidUnless(mode in device.capabilities.spatialModes)
    }

    data class SpatialContent(val content: String) : HeadsetCommand(DeviceSetting.SPATIAL_CONTENT, FieldValue.Text(content)) {
        override fun checkOptions(device: DeviceState): ControlError? = invalidUnless(content in device.capabilities.spatialContents)
    }

    companion object {
        /** Decode only at the process boundary; ordinary callers construct a typed intent directly. */
        fun decode(setting: DeviceSetting, value: FieldValue): HeadsetCommand? = when (value) {
            is FieldValue.Toggle -> decodeToggle(setting, value.value)

            is FieldValue.Number -> decodeLevel(setting, value.value)

            is FieldValue.Text -> decodeChoice(setting, value.value)

            is FieldValue.IntegerList -> if (setting == DeviceSetting.CUSTOM_EQ) CustomEqualizer(value.values) else null

            is FieldValue.NoiseCycle -> decodeNoiseCycle(setting, value)

            is FieldValue.TouchAssignment -> when (setting) {
                DeviceSetting.TOUCH_BINDING -> TouchBinding(value.side, value.gesture, value.action)
                DeviceSetting.TAP_BINDING -> TapBinding(value.side, value.gesture, value.action)
                else -> null
            }

            is FieldValue.Firmware, is FieldValue.TouchKeys -> null
        }

        private fun decodeNoiseCycle(setting: DeviceSetting, value: FieldValue.NoiseCycle): HeadsetCommand? {
            if (setting != DeviceSetting.NOISE_CYCLE || value.flags.values.any { it == null }) return null
            return NoiseCycle(value.flags.mapValues { requireNotNull(it.value) })
        }

        private fun decodeToggle(setting: DeviceSetting, enabled: Boolean): HeadsetCommand? = when (setting) {
            DeviceSetting.GAME_MODE -> GameMode(enabled)
            DeviceSetting.MULTIPOINT -> Multipoint(enabled)
            DeviceSetting.TOUCH_ENABLED -> TouchEnabled(enabled)
            DeviceSetting.EAR_DETECTION -> EarDetection(enabled)
            DeviceSetting.TAP_ENABLED -> TapEnabled(enabled)
            DeviceSetting.SPATIAL_AUDIO -> SpatialAudio(enabled)
            DeviceSetting.EAR_ADAPTIVE -> EarAdaptive(enabled)
            DeviceSetting.INDEPENDENT_MIC_ACTIVATION -> IndependentMicActivation(enabled)
            DeviceSetting.INDEPENDENT_MIC_MODE -> IndependentMicMode(enabled)
            else -> null
        }

        private fun decodeLevel(setting: DeviceSetting, level: Int): HeadsetCommand? = when (setting) {
            DeviceSetting.ANC_LEVEL -> AncLevel(level)
            DeviceSetting.TRANSPARENCY_LEVEL -> TransparencyLevel(level)
            DeviceSetting.PROMPT_VOLUME -> PromptVolume(level)
            DeviceSetting.GAIN -> Gain(level)
            DeviceSetting.HEARING_PROTECTION -> HearingProtection(level)
            DeviceSetting.BASS_BOOST -> BassBoost(level)
            else -> null
        }

        private fun decodeChoice(setting: DeviceSetting, choice: String): HeadsetCommand? = when (setting) {
            DeviceSetting.NOISE_MODE -> HeadsetNoiseMode.fromId(choice)?.let(::NoiseMode)
            DeviceSetting.EQ -> EqualizerPreset(choice)
            DeviceSetting.AUDIO_MODE -> AudioMode(choice)
            DeviceSetting.PROMPT_LANGUAGE -> PromptLanguage(choice)
            DeviceSetting.SPATIAL_MODE -> SpatialMode(choice)
            DeviceSetting.SPATIAL_CONTENT -> SpatialContent(choice)
            else -> null
        }
    }
}

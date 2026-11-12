package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting

// F8 setting encoders: each returns the command byte, the readback tag and the data bytes of one frame.
internal fun settingFrame(
    profile: ModelProfile,
    setting: DeviceSetting,
    value: FieldValue,
    capabilities: DeviceCapabilities,
): Triple<Int, Int, IntArray> = when (setting) {
    DeviceSetting.EQ -> eqFrame(value, capabilities)

    DeviceSetting.GAME_MODE, DeviceSetting.EAR_DETECTION, DeviceSetting.TOUCH_ENABLED -> toggleFrame(setting, value)

    DeviceSetting.NOISE_MODE, DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL ->
        noiseFrame(profile, setting, value, capabilities)

    DeviceSetting.PROMPT_LANGUAGE -> languageFrame(value, capabilities)

    DeviceSetting.AUDIO_MODE, DeviceSetting.MULTIPOINT -> audioFrame(setting, value, capabilities)

    DeviceSetting.TOUCH_BINDING -> touchFrame(profile, value)

    DeviceSetting.PROMPT_VOLUME -> promptVolumeFrame(value, capabilities)

    else -> throw UnsupportedOperationException("该型号不支持此设置")
}

private fun eqFrame(value: FieldValue, capabilities: DeviceCapabilities): Triple<Int, Int, IntArray> {
    require(value is FieldValue.Text && value.value in capabilities.equalizer!!.presets)
    return Triple(0x20, 4, intArrayOf(0, mapOf("hifi" to 0, "pop" to 1, "rock" to 2).getValue(value.value)))
}

private fun languageFrame(value: FieldValue, capabilities: DeviceCapabilities): Triple<Int, Int, IntArray> {
    require(value is FieldValue.Text && value.value in capabilities.promptLanguages)
    return Triple(0x29, 10, intArrayOf(if (value.value == "chinese") 0 else 1))
}

private fun touchFrame(profile: ModelProfile, value: FieldValue): Triple<Int, Int, IntArray> {
    val binding = validatedTouchAssignment(value, profile)
    val gesture = listOf("single", "double", "triple", "long").indexOf(binding.gesture)
    val slot = gesture * 2 + if (binding.side == "left") 1 else 2
    val action = f8TouchActions.entries.first { it.value == binding.action }.key
    return Triple(0x22, 5, intArrayOf(slot, 1, action))
}

private fun toggleFrame(setting: DeviceSetting, value: FieldValue): Triple<Int, Int, IntArray> {
    require(value is FieldValue.Toggle)
    val (command, tag) = when (setting) {
        DeviceSetting.GAME_MODE -> 0x25 to 8
        DeviceSetting.EAR_DETECTION -> 0x26 to 9
        else -> 0x33 to 22
    }
    return Triple(command, tag, intArrayOf(if (value.value) 1 else 0))
}

/** `setDingLevel`: `00 32 01 00 01 0N`, one decimal-digit level; the readback is the `13` query. */
private fun promptVolumeFrame(value: FieldValue, capabilities: DeviceCapabilities): Triple<Int, Int, IntArray> {
    require(value is FieldValue.Number && value.value in ProtocolFamily.F8.promptVolumeLevels) {
        "F8 提示音仅支持 0～9 档"
    }
    if (value.value !in capabilities.promptVolumeLevels) throw UnsupportedOperationException("该型号不支持此提示音档位")
    return Triple(0x32, 19, intArrayOf(value.value))
}

private fun noiseFrame(
    profile: ModelProfile,
    setting: DeviceSetting,
    value: FieldValue,
    capabilities: DeviceCapabilities,
): Triple<Int, Int, IntArray> {
    if (setting == DeviceSetting.NOISE_MODE) {
        require(value is FieldValue.Text && value.value in capabilities.noiseControl!!.modes)
        val modes = mapOf("anc" to 1, "transparency" to 2, "wind" to 0, "normal" to profile.spec.f8NoiseNormalWire)
        return Triple(0x2c, 12, intArrayOf(modes.getValue(value.value)))
    }
    require(value is FieldValue.Number && value.value in capabilities.noiseControl!!.levelCapabilities(setting)!!.levels)
    val wire = mapOf(1 to 2, 3 to 1, 5 to 0).getValue(value.value)
    val command = if (setting == DeviceSetting.ANC_LEVEL) 0x30 else 0x31
    val tag = if (setting == DeviceSetting.ANC_LEVEL) 17 else 18
    return Triple(command, tag, intArrayOf(wire))
}

private fun audioFrame(setting: DeviceSetting, value: FieldValue, capabilities: DeviceCapabilities): Triple<Int, Int, IntArray> {
    val wire = if (setting == DeviceSetting.MULTIPOINT) {
        require(value is FieldValue.Toggle)
        if (value.value) 0 else 2
    } else {
        require(value is FieldValue.Text && value.value in capabilities.audioControl!!.modes)
        if (value.value == "ldac") 1 else 2
    }
    return Triple(0x2b, 11, intArrayOf(wire))
}

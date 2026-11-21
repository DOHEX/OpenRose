package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.models.ModelProtocol

/** ZT models with independent multipoint carry the custom curve in slot 3 instead of the light preset. */
internal val ModelProtocol.Zt.eqPresets: List<String>
    get() = if (independentMultipoint) {
        listOf("pop", "hifi", "rock", "custom")
    } else {
        listOf("pop", "hifi", "rock", "light")
    }

internal data class ZtWrite(val writeKey: String, val readKey: String, val payload: ByteArray)

private typealias ZtEncoder = ModelProtocol.Zt.(ModelProfile, FieldValue, FieldValue.Firmware?) -> ZtWrite

internal fun ModelProtocol.Zt.settingFrame(
    profile: ModelProfile,
    setting: DeviceSetting,
    value: FieldValue,
    firmware: FieldValue.Firmware?,
): ZtWrite {
    val encode = writers[setting] ?: throw UnsupportedOperationException("该型号不支持此设置")
    return encode(this, profile, value, firmware)
}

private val writers = mapOf<DeviceSetting, ZtEncoder>(
    DeviceSetting.NOISE_MODE to { _, value, _ -> ZtWrite("0682", "0602", noiseModeBytes(value)) },
    DeviceSetting.ANC_LEVEL to { _, value, _ -> ZtWrite("0687", "0607", noiseLevelByte(value)) },
    DeviceSetting.TRANSPARENCY_LEVEL to { _, value, _ -> ZtWrite("0684", "0604", noiseLevelByte(value)) },
    DeviceSetting.NOISE_CYCLE to { _, value, _ -> ZtWrite("0685", "0605", noiseCycleBytes(value)) },
    DeviceSetting.EQ to { profile, value, firmware -> ZtWrite("0281", "0201", presetByte(profile, firmware, value)) },
    DeviceSetting.GAIN to { profile, value, firmware -> ZtWrite("0283", "0203", gainByte(profile, firmware, value)) },
    DeviceSetting.PROMPT_VOLUME to { _, value, _ -> promptVolumeFrame(value) },
    DeviceSetting.AUDIO_MODE to { _, value, _ -> ZtWrite("0881", "0801", audioModeByte(value)) },
    DeviceSetting.MULTIPOINT to { _, value, _ -> multipointFrame(value) },
    DeviceSetting.TOUCH_BINDING to { profile, value, _ -> ZtWrite("0481", "0401", touchBytes(profile, value)) },
    DeviceSetting.GAME_MODE to { _, value, _ -> ZtWrite("0683", "0603", invertedToggle(value, "游戏模式需要明确开关")) },
    DeviceSetting.TOUCH_ENABLED to { _, value, _ -> ZtWrite("0a81", "0a01", invertedToggle(value, "触控和检测功能需要明确开关")) },
    DeviceSetting.EAR_DETECTION to { _, value, _ -> ZtWrite("0c81", "0c01", invertedToggle(value, "触控和检测功能需要明确开关")) },
)

private fun ModelProtocol.Zt.noiseModeBytes(value: FieldValue): ByteArray {
    require(value is FieldValue.Text) { "降噪模式需要模式名称" }
    val modes = if (threeByteNoise) {
        mapOf("anc" to "010000", "normal" to "000001", "transparency" to "000100")
    } else {
        mapOf("anc" to "01000000", "normal" to "00010000", "wind" to "00000100", "transparency" to "00000001")
    }
    return modes[value.value]?.bytes() ?: throw UnsupportedOperationException("该型号不支持该降噪模式")
}

private fun ModelProtocol.Zt.noiseLevelByte(value: FieldValue): ByteArray {
    require(value is FieldValue.Number) { "档位需要整数" }
    val level = ztNoiseLevels.indexOf(value.value)
    require(level >= 0) { "ZT 档位仅支持 1、3、5" }
    return byteArrayOf((level + levelWriteOffset).toByte())
}

private fun noiseCycleBytes(value: FieldValue): ByteArray {
    val flags = validatedNoiseCycle(value).flags
    return ztCycleOrder.map { if (flags.getValue(it) == true) 1.toByte() else 0.toByte() }.toByteArray()
}

private fun ModelProtocol.Zt.presetByte(profile: ModelProfile, firmware: FieldValue.Firmware?, value: FieldValue): ByteArray {
    require(value is FieldValue.Text) { "均衡器需要预设名称" }
    val preset = eqPresets.indexOf(value.value)
    if (preset < 0 || value.value !in profile.capabilities(firmware).equalizer?.presets.orEmpty()) {
        throw UnsupportedOperationException("该型号不支持该均衡器预设")
    }
    return byteArrayOf(preset.toByte())
}

private fun gainByte(profile: ModelProfile, firmware: FieldValue.Firmware?, value: FieldValue): ByteArray {
    require(value is FieldValue.Number && value.value in profile.capabilities(firmware).gainLevels) { "该型号不支持此增益档位" }
    return byteArrayOf((value.value + 1).toByte())
}

private fun ModelProtocol.Zt.promptVolumeFrame(value: FieldValue): ZtWrite {
    require(value is FieldValue.Number && value.value in ProtocolFamily.ZT.promptVolumeLevels) { "ZT 提示音仅支持 1～5 档" }
    return if (promptVolumeDirect) {
        ZtWrite("0e03", "0e03", byteArrayOf(value.value.toByte()))
    } else {
        ZtWrite("0e83", "0e03", byteArrayOf((value.value - 1).toByte()))
    }
}

private fun ModelProtocol.Zt.audioModeByte(value: FieldValue): ByteArray {
    require(value is FieldValue.Text) { "音频模式需要配置名称" }
    val mode = audioModes.indexOf(value.value)
    if (mode < 0) throw UnsupportedOperationException("该型号不支持该音频配置")
    return byteArrayOf(mode.toByte())
}

private fun ModelProtocol.Zt.multipointFrame(value: FieldValue): ZtWrite {
    require(value is FieldValue.Toggle) { "多点连接需要明确开关" }
    return if (independentMultipoint) {
        ZtWrite("0882", "0802", byteArrayOf(if (value.value) 1 else 0))
    } else {
        ZtWrite("0881", "0801", byteArrayOf(if (value.value) 0 else 1))
    }
}

private fun touchBytes(profile: ModelProfile, value: FieldValue): ByteArray {
    val binding = validatedTouchAssignment(value, profile.capabilities().touchControl)
    val side = if (binding.side == "left") 0 else 1
    val gesture = ztGestures.indexOf(binding.gesture)
    val action = ztActions.indexOf(binding.action)
    return byteArrayOf(side.toByte(), gesture.toByte(), action.toByte())
}

private fun invertedToggle(value: FieldValue, requirement: String): ByteArray {
    require(value is FieldValue.Toggle) { requirement }
    return byteArrayOf(if (value.value) 0 else 1)
}

internal val ztNoiseLevels = listOf(1, 3, 5)
internal val ztCycleOrder = listOf("normal", "transparency", "wind", "anc")

internal val ModelProtocol.Zt.audioModes: List<String>
    get() = if (independentMultipoint) listOf("aac_sbc", "ldac", "lhdc", "ldac_lhdc") else listOf("aac_sbc", "ldac")

internal val ztGestures = listOf("single", "double", "triple", "long")
internal val ztActions = listOf(
    "volume_up",
    "volume_down",
    "previous_track",
    "next_track",
    "voice_assistant",
    "play_pause",
    "none",
    "noise_cycle",
)

package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities

/** One validated record, with the fields the device must report in reply. */
internal data class RsV2Write(val type: Byte, val payload: ByteArray, val expected: Map<DeviceField, FieldValue>)

private class RsV2Writer(
    val type: Int,
    val expected: (FieldValue) -> Map<DeviceField, FieldValue> = { value ->
        mapOf(rsCommonV2Fields.getValue(byteArrayOf(type.toByte()).hex()).single() to value)
    },
    val payload: DeviceCapabilities.(FieldValue) -> ByteArray,
)

internal fun rsV2Setting(capabilities: DeviceCapabilities, setting: DeviceSetting, value: FieldValue): RsV2Write {
    val writer = writers[setting] ?: throw UnsupportedOperationException(
        if (setting == DeviceSetting.SPATIAL_AUDIO) "该型号不支持空间音频总开关" else "该型号不支持此设置",
    )
    return RsV2Write(writer.type.toByte(), writer.payload(capabilities, value), writer.expected(value))
}

private val writers = mapOf(
    DeviceSetting.NOISE_MODE to RsV2Writer(0x09) { value ->
        require(value is FieldValue.Text) { "降噪模式需要模式名称" }
        val mode = rsV2NoiseModes.indexOf(value.value) + 1
        if (mode == 0 || value.value !in noiseControl?.modes.orEmpty()) throw UnsupportedOperationException("该型号不支持该降噪模式")
        byteArrayOf(mode.toByte())
    },
    DeviceSetting.ANC_LEVEL to RsV2Writer(0x2c) { value -> noiseLevel(value, noiseControl?.ancLevel) },
    DeviceSetting.TRANSPARENCY_LEVEL to RsV2Writer(0x2d) { value -> noiseLevel(value, noiseControl?.transparencyLevel) },
    DeviceSetting.NOISE_CYCLE to RsV2Writer(0x36) { value ->
        val flags = validatedNoiseCycle(value, noiseControl?.cycleModes.orEmpty()).flags
        rsV2CycleOrder.map { if (flags[it] == true) 1.toByte() else 0.toByte() }.toByteArray()
    },
    DeviceSetting.EQ to RsV2Writer(0x2a) { value ->
        require(value is FieldValue.Text) { "均衡器需要预设名称" }
        if (value.value !in equalizer?.presets.orEmpty()) throw UnsupportedOperationException("该型号当前固件不支持该均衡器预设")
        byteArrayOf(rsV2PresetCodes.getValue(value.value).toByte())
    },
    DeviceSetting.BASS_BOOST to RsV2Writer(
        0x2a,
        expected = { mapOf(DeviceField.EQ to FieldValue.Text("custom_slot"), DeviceField.EQ_SLOT to it) },
    ) { value ->
        require(value is FieldValue.Number && value.value in 0..4) { "低音增强仅支持 0～4 档" }
        if (value.value !in bassBoostLevels) throw UnsupportedOperationException("该型号不支持低音增强")
        byteArrayOf(5, value.value.toByte())
    },
    DeviceSetting.PROMPT_LANGUAGE to RsV2Writer(0x31) { value ->
        require(value is FieldValue.Text && value.value in rsV2PromptLanguages) { "提示音语言无效" }
        if (value.value !in promptLanguages) throw UnsupportedOperationException("该型号不支持提示音语言")
        byteArrayOf(rsV2PromptLanguages.indexOf(value.value).toByte())
    },
    DeviceSetting.GAIN to RsV2Writer(0x45) { value -> levelByte(value, "增益", 0..2, gainLevels) },
    DeviceSetting.HEARING_PROTECTION to RsV2Writer(0x46) { value -> levelByte(value, "听力保护", 0..6, hearingProtectionLevels) },
    DeviceSetting.PROMPT_VOLUME to RsV2Writer(0x2e) { value ->
        require(value is FieldValue.Number) { "提示音需要档位整数" }
        require(value.value in ProtocolFamily.RS_COMMON_V2.promptVolumeLevels) { "RS Common V2 提示音仅支持 0～5 档" }
        if (value.value !in promptVolumeLevels) throw UnsupportedOperationException("该型号当前固件不支持此提示音档位")
        byteArrayOf(value.value.toByte())
    },
    DeviceSetting.GAME_MODE to RsV2Writer(0x0e) { value -> toggleByte(value, "游戏模式需要明确开关") },
    DeviceSetting.AUDIO_MODE to RsV2Writer(0x2b) { value ->
        require(value is FieldValue.Text) { "音频模式需要配置名称" }
        val mode = rsV2AudioModes.indexOf(value.value)
        if (mode < 0 || value.value !in audioControl?.modes.orEmpty()) throw UnsupportedOperationException("该型号不支持该音频配置")
        byteArrayOf(mode.toByte())
    },
    DeviceSetting.MULTIPOINT to RsV2Writer(0x32) { value -> toggleByte(value, "多点连接需要明确开关") },
    DeviceSetting.TOUCH_ENABLED to RsV2Writer(0x07) { value -> toggleByte(value, "触控和检测功能需要明确开关", on = 0) },
    DeviceSetting.EAR_DETECTION to RsV2Writer(0x08) { value -> toggleByte(value, "触控和检测功能需要明确开关", on = 0) },
    DeviceSetting.TAP_ENABLED to RsV2Writer(0x3b) { value ->
        if (DeviceSetting.TAP_ENABLED !in writableSettings) throw UnsupportedOperationException("该型号不支持轻拍控制")
        toggleByte(value, "轻拍控制需要明确开关")
    },
    DeviceSetting.TOUCH_BINDING to RsV2Writer(0x01) { value -> bindingBytes(value, touchControl) },
    DeviceSetting.TAP_BINDING to RsV2Writer(0x3c) { value -> bindingBytes(value, tapControl) },
    DeviceSetting.SPATIAL_MODE to RsV2Writer(0x38) { value -> spatialByte(value, spatialModes, rsV2SpatialModes) },
    DeviceSetting.SPATIAL_CONTENT to RsV2Writer(0x3d) { value -> spatialByte(value, spatialContents, rsV2SpatialContents) },
    DeviceSetting.EAR_ADAPTIVE to RsV2Writer(0x3a) { value -> supportedToggle(DeviceSetting.EAR_ADAPTIVE, value) },
    DeviceSetting.INDEPENDENT_MIC_ACTIVATION to RsV2Writer(0x49, { mapOf(DeviceField.INDEPENDENT_MIC_ACTIVATION to it) }) { value ->
        byteArrayOf(0) + supportedToggle(DeviceSetting.INDEPENDENT_MIC_ACTIVATION, value)
    },
    DeviceSetting.INDEPENDENT_MIC_MODE to RsV2Writer(0x49, { mapOf(DeviceField.INDEPENDENT_MIC_MODE to it) }) { value ->
        byteArrayOf(1) + supportedToggle(DeviceSetting.INDEPENDENT_MIC_MODE, value)
    },
)

private fun noiseLevel(value: FieldValue, capability: NoiseLevelCapabilities?): ByteArray {
    require(value is FieldValue.Number) { "档位需要整数" }
    val levels = capability?.levels ?: throw UnsupportedOperationException("该型号不支持此档位设置")
    require(value.value in levels) { "档位仅支持 ${levels.sorted().joinToString("、")}" }
    return byteArrayOf(value.value.toByte())
}

private fun levelByte(value: FieldValue, name: String, range: IntRange, supported: Set<Int>): ByteArray {
    require(value is FieldValue.Number) { "${name}需要档位整数" }
    require(value.value in range) { "RS Common V2 ${name}档位仅支持 ${range.first}～${range.last}" }
    if (value.value !in supported) throw UnsupportedOperationException("该型号当前固件不支持${name}设置")
    return byteArrayOf(value.value.toByte())
}

private fun toggleByte(value: FieldValue, requirement: String, on: Int = 1): ByteArray {
    require(value is FieldValue.Toggle) { requirement }
    return byteArrayOf((if (value.value) on else 1 - on).toByte())
}

private fun DeviceCapabilities.supportedToggle(setting: DeviceSetting, value: FieldValue): ByteArray {
    if (setting !in writableSettings) throw UnsupportedOperationException("该型号不支持此设置")
    return toggleByte(value, "需要明确开关")
}

private fun bindingBytes(value: FieldValue, capability: TouchControlCapabilities?): ByteArray {
    val binding = validatedTouchAssignment(value, capability)
    val slot = rsV2Gestures.indexOf(binding.gesture) + 1 + if (binding.side == "right") 0x10 else 0
    return byteArrayOf(slot.toByte(), rsV2Actions.indexOf(binding.action).toByte())
}

private fun spatialByte(value: FieldValue, supported: Set<String>, wireOrder: List<String>): ByteArray {
    require(value is FieldValue.Text && value.value in supported) { "该型号不支持此空间音频选项" }
    val wire = wireOrder.indexOf(value.value)
    require(wire >= 0) { "未知空间音频选项" }
    return byteArrayOf(wire.toByte())
}

internal val rsV2PromptLanguages = listOf("chinese", "english")
internal val rsV2CycleOrder = listOf("anc", "wind", "normal", "transparency")
private val rsV2PresetCodes = mapOf("hifi" to 0, "pop" to 1, "rock" to 2, "light" to 3, "eap" to 6, "game_1" to 0xa0)

internal val rsV2SpatialModes = listOf("off", "head_tracking", "fixed")
internal val rsV2SpatialContents = listOf("tv", "music", "movie", "game")
internal val rsV2AudioModes = listOf("aac_sbc", "ldac", "lhdc", "lc3", "aptx", "aptx_hd", "aptx_adaptive")

internal val rsV2Gestures = listOf("single", "double", "triple", "long")
internal val rsV2Actions = listOf(
    "none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "game_mode", "voice_assistant", "noise_cycle",
)

package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting

internal enum class RsCommonInit { DEFAULT, BUDS_FREE, OPEN_FEEL, CAMBRIAN }

internal val rsCommonFields = linkedMapOf(
    "01" to setOf(DeviceField.TOUCH_KEYS), "07" to setOf(DeviceField.TOUCH_ENABLED),
    "08" to setOf(DeviceField.EAR_DETECTION), "09" to setOf(DeviceField.NOISE_MODE),
    "0c" to setOf(
        DeviceField.LEFT_BATTERY,
        DeviceField.RIGHT_BATTERY,
        DeviceField.CASE_BATTERY,
        DeviceField.LEFT_IN_CASE,
        DeviceField.RIGHT_IN_CASE,
    ),
    "0d" to setOf(DeviceField.FIRMWARE), "0e" to setOf(DeviceField.GAME_MODE),
    "2a" to setOf(DeviceField.EQ), "2b" to setOf(DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT),
    "2c" to setOf(DeviceField.ANC_LEVEL), "2d" to setOf(DeviceField.TRANSPARENCY_LEVEL),
    "2e" to setOf(DeviceField.PROMPT_VOLUME), "33" to setOf(DeviceField.SPATIAL_AUDIO),
)

/** Legacy RS Common has fixed sequence bytes and no checksum. */
internal class RsCommonCodec(override val profile: ModelProfile) : ProtocolCodec {
    private var buffer = byteArrayOf()
    private val info = "010708090c0d0e122a2b2c2d2e33".bytes()
    private val touchActionNames = profile.spec.rsCommonTouchActions.entries.associate { it.value to it.key }

    private fun frame(
        body: ByteArray,
        sequence: Int = 0,
    ): ByteArray = (
        byteArrayOf(0xff.toByte(), sequence.toByte(), body.size.toByte()) + body + byteArrayOf(0xaa.toByte())
        )

    private fun query(ids: ByteArray, sequence: Int = 0, delay: Long = 0): ProtocolQuery {
        val keys = ids.map { byteArrayOf(it).hex() }.filter { it in rsCommonFields }.toSet()
        return ProtocolQuery(
            "fa" + ids.hex(),
            frame(byteArrayOf(0xfa.toByte()) + ids, sequence),
            keys,
            keys.flatMap { rsCommonFields.getValue(it) }.toSet(),
            delayBeforeMillis = delay,
        )
    }

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 19) { "RS Common getInfo requires 19-byte write capacity" }
        val first = query(info)
        return when (profile.spec.rsCommonInit) {
            RsCommonInit.DEFAULT -> listOf(first)
            RsCommonInit.BUDS_FREE -> listOf(first, query("2a090c0e07012b".bytes(), 5))
            RsCommonInit.OPEN_FEEL -> listOf(first, query("2b".bytes()))
            RsCommonInit.CAMBRIAN -> listOf(first, query(info, delay = 500))
        }
    }

    override fun prepareSetting(setting: DeviceSetting, value: FieldValue, firmware: FieldValue.Firmware?): ProtocolSetting {
        val caps = profile.capabilities(firmware)
        if (setting !in caps.writableSettings) throw UnsupportedOperationException("该型号不支持此设置")
        if (setting == DeviceSetting.TOUCH_BINDING) return prepareTouch(value)
        val (command, response, data) = settingCommand(setting, value, caps)
        return ProtocolSetting(
            listOf(frame(byteArrayOf(command.toByte(), data.toByte()))),
            query(byteArrayOf(response.toByte())),
            mapOf(settingField(setting) to value),
        )
    }

    private fun prepareTouch(value: FieldValue): ProtocolSetting {
        val binding = validatedTouchAssignment(value, profile)
        val slot = listOf("single", "double", "triple").indexOf(binding.gesture) + 1 + if (binding.side == "right") 16 else 0
        val action = profile.spec.rsCommonTouchActions[binding.action]
            ?: throw UnsupportedOperationException("该型号不支持此触控动作")
        return ProtocolSetting(
            listOf(frame(byteArrayOf(1, slot.toByte(), action.toByte()))),
            query(byteArrayOf(1)),
            mapOf(DeviceField.TOUCH_KEYS to binding),
        )
    }

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> {
        buffer += bytes
        val replies = mutableListOf<ProtocolReply>()
        var raw = nextFrame()
        while (raw != null) {
            if (raw.size >= 5 && raw.last().u() == 0xaa) replies += decode(raw)
            raw = nextFrame()
        }
        return replies
    }

    private fun nextFrame(): ByteArray? {
        val start = buffer.indexOf(0xff.toByte())
        if (start < 0) {
            buffer = byteArrayOf()
            return null
        }
        if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
        val size = if (buffer.size < 3) Int.MAX_VALUE else buffer[2].u() + 4
        return if (buffer.size < size) {
            null
        } else {
            val raw = buffer.copyOfRange(0, size)
            buffer = buffer.copyOfRange(size, buffer.size)
            raw
        }
    }

    private fun decode(raw: ByteArray): ProtocolReply {
        val tag = raw[3].u()
        val value = raw.copyOfRange(4, raw.size - 1)
        val key = byteArrayOf(tag.toByte()).hex()
        val out = when (tag) {
            0x0c -> decodeBattery(value)
            0x0d -> decodeFirmware(value)
            0x01 -> decodeTouch(value)
            0x07, 0x08, 0x0e, 0x33 -> decodeToggle(tag, value)
            0x09, 0x2a, 0x2b -> decodeMode(tag, value)
            0x2c, 0x2d, 0x2e -> decodeLevel(tag, value)
            else -> emptyMap()
        }
        return ProtocolReply(raw.hex(), if (key in rsCommonFields) setOf(key) else emptySet(), out)
    }

    private fun decodeBattery(value: ByteArray): Map<DeviceField, FieldReading> {
        if (value.size < 3) return emptyMap()
        val fields = listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)
        val indices = when (profile.batteryLayout) {
            BatteryLayout.SINGLE_UNIT -> listOf(2)
            BatteryLayout.EARBUDS_ONLY -> listOf(0, 1)
            BatteryLayout.EARBUDS_AND_CASE -> listOf(0, 1, 2)
        }
        val out = linkedMapOf<DeviceField, FieldReading>()
        for (i in indices) {
            val byte = value[i].u()
            val amount = if (i < 2) byte and 127 else byte
            val source = byteArrayOf(value[i])
            out[fields[i]] = reading(amount.takeIf { it in 1..100 }?.let { FieldValue.Number(it) }, source)
            if (i < 2) {
                val field = if (i == 0) DeviceField.LEFT_IN_CASE else DeviceField.RIGHT_IN_CASE
                out[field] = reading(if (byte == 255) null else FieldValue.Toggle(byte and 128 != 0), source)
            }
        }
        return out
    }

    private fun decodeTouch(value: ByteArray): Map<DeviceField, FieldReading> {
        if (value.size < 16) return emptyMap()
        // RoseLink 按键解析读取无空格报文的字符下标 11/15/19/31/35/39 的单字符十六进制值；
        // 换算成 value 的字节下标后取低半字节。设备响应未实测。
        val positions = listOf(1, 3, 5, 11, 13, 15)
        val bindings = positions.mapIndexed { index, pos ->
            val action = value[pos].u() and 15
            TouchBinding(
                if (index < 3) "left" else "right",
                listOf("single", "double", "triple")[index % 3],
                touchActionNames[action],
                action,
            )
        }
        return mapOf(DeviceField.TOUCH_KEYS to reading(FieldValue.TouchKeys(bindings), value))
    }
    override fun reset() {
        buffer = byteArrayOf()
    }
}
private fun settingCommand(
    setting: DeviceSetting,
    value: FieldValue,
    caps: io.github.dohex.openrose.core.DeviceCapabilities,
): Triple<Int, Int, Int> = when (setting) {
    DeviceSetting.NOISE_MODE -> {
        require(value is FieldValue.Text && value.value in caps.noiseControl!!.modes)
        Triple(0x12, 0x09, mapOf("anc" to 2, "normal" to 4, "transparency" to 6, "wind" to 8).getValue(value.value))
    }

    DeviceSetting.EQ -> {
        require(value is FieldValue.Text && value.value in caps.equalizer!!.presets)
        Triple(0x2a, 0x2a, mapOf("pop" to 1, "hifi" to 0, "rock" to 2).getValue(value.value))
    }

    DeviceSetting.AUDIO_MODE -> {
        require(value is FieldValue.Text && value.value in caps.audioControl!!.modes)
        Triple(0x2b, 0x2b, listOf("aac_sbc", "ldac", "lhdc", "lc3").indexOf(value.value))
    }

    DeviceSetting.GAME_MODE, DeviceSetting.TOUCH_ENABLED, DeviceSetting.MULTIPOINT, DeviceSetting.SPATIAL_AUDIO ->
        toggleCommand(setting, value)

    DeviceSetting.TRANSPARENCY_LEVEL, DeviceSetting.ANC_LEVEL, DeviceSetting.PROMPT_VOLUME ->
        levelCommand(setting, value, caps)

    else -> throw UnsupportedOperationException("该型号不支持此设置")
}

private fun toggleCommand(setting: DeviceSetting, value: FieldValue): Triple<Int, Int, Int> {
    require(value is FieldValue.Toggle)
    return when (setting) {
        DeviceSetting.GAME_MODE -> Triple(0x0e, 0x0e, if (value.value) 1 else 0)
        DeviceSetting.TOUCH_ENABLED -> Triple(0x0e, 0x07, if (value.value) 0 else 2)
        DeviceSetting.MULTIPOINT -> Triple(0x2b, 0x2b, if (value.value) 4 else 0)
        DeviceSetting.SPATIAL_AUDIO -> Triple(0x33, 0x33, if (value.value) 1 else 0)
        else -> throw UnsupportedOperationException("该型号不支持此设置")
    }
}

private fun levelCommand(
    setting: DeviceSetting,
    value: FieldValue,
    caps: io.github.dohex.openrose.core.DeviceCapabilities,
): Triple<Int, Int, Int> {
    require(value is FieldValue.Number)
    val levels = if (setting == DeviceSetting.PROMPT_VOLUME) {
        caps.promptVolumeLevels
    } else {
        caps.noiseControl?.levelCapabilities(setting)?.levels.orEmpty()
    }
    require(value.value in levels)
    return when (setting) {
        DeviceSetting.ANC_LEVEL -> Triple(0x58, 0x2c, value.value)
        DeviceSetting.TRANSPARENCY_LEVEL -> Triple(0x5a, 0x2d, value.value)
        DeviceSetting.PROMPT_VOLUME -> Triple(0x5c, 0x2e, value.value)
        else -> throw UnsupportedOperationException("该型号不支持此设置")
    }
}

private fun settingField(setting: DeviceSetting): DeviceField = when (setting) {
    DeviceSetting.NOISE_MODE -> DeviceField.NOISE_MODE
    DeviceSetting.EQ -> DeviceField.EQ
    DeviceSetting.GAME_MODE -> DeviceField.GAME_MODE
    DeviceSetting.TOUCH_ENABLED -> DeviceField.TOUCH_ENABLED
    DeviceSetting.MULTIPOINT -> DeviceField.MULTIPOINT
    DeviceSetting.AUDIO_MODE -> DeviceField.AUDIO_MODE
    DeviceSetting.ANC_LEVEL -> DeviceField.ANC_LEVEL
    DeviceSetting.TRANSPARENCY_LEVEL -> DeviceField.TRANSPARENCY_LEVEL
    DeviceSetting.PROMPT_VOLUME -> DeviceField.PROMPT_VOLUME
    DeviceSetting.SPATIAL_AUDIO -> DeviceField.SPATIAL_AUDIO
    else -> error("Unsupported setting")
}
private fun decodeFirmware(value: ByteArray): Map<DeviceField, FieldReading> {
    if (value.size < 3) return emptyMap()
    val parts = listOf(value[0].u() and 15, value[1].u(), value[2].u())
    return mapOf(DeviceField.FIRMWARE to reading(FieldValue.Firmware(parts, parts.joinToString("")), value))
}

private fun decodeToggle(tag: Int, value: ByteArray): Map<DeviceField, FieldReading> {
    val field = when (tag) {
        0x07 -> DeviceField.TOUCH_ENABLED
        0x08 -> DeviceField.EAR_DETECTION
        0x0e -> DeviceField.GAME_MODE
        else -> DeviceField.SPATIAL_AUDIO
    }
    val n = value.firstOrNull()?.u()
    return mapOf(field to reading(n?.takeIf { it in 0..1 }?.let { FieldValue.Toggle(it != 0) }, value))
}

private fun decodeMode(tag: Int, value: ByteArray): Map<DeviceField, FieldReading> {
    val n = value.firstOrNull()?.u()
    if (tag == 0x2b) {
        val mode = n?.let { listOf("aac_sbc", "ldac", "lhdc", "lc3").getOrNull(it) }
        return mapOf(
            DeviceField.AUDIO_MODE to reading(mode?.let { FieldValue.Text(it) }, value),
            DeviceField.MULTIPOINT to reading(n?.takeIf { it in 0..4 }?.let { FieldValue.Toggle(it == 4) }, value),
        )
    }
    val names = if (tag == 0x09) {
        mapOf(1 to "anc", 2 to "normal", 3 to "transparency", 4 to "wind")
    } else {
        mapOf(0 to "hifi", 1 to "pop", 2 to "rock")
    }
    val field = if (tag == 0x09) DeviceField.NOISE_MODE else DeviceField.EQ
    return mapOf(field to reading(names[n]?.let { FieldValue.Text(it) }, value))
}

private fun decodeLevel(tag: Int, value: ByteArray): Map<DeviceField, FieldReading> {
    val field = when (tag) {
        0x2c -> DeviceField.ANC_LEVEL
        0x2d -> DeviceField.TRANSPARENCY_LEVEL
        else -> DeviceField.PROMPT_VOLUME
    }
    val n = value.firstOrNull()?.u()
    return mapOf(field to reading(n?.takeIf { it in 1..5 }?.let { FieldValue.Number(it) }, value))
}
private fun reading(
    value: FieldValue?,
    raw: ByteArray,
): FieldReading = if (value == null) {
    FieldReading.Unknown(raw.hex())
} else {
    FieldReading.Known(value, raw.hex())
}

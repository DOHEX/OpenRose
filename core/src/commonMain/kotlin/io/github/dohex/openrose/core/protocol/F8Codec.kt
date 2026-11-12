package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting

/** F8 has a five-byte header and no application checksum. */
internal val f8Fields = linkedMapOf(
    "01" to setOf(
        DeviceField.LEFT_BATTERY,
        DeviceField.RIGHT_BATTERY,
        DeviceField.CASE_BATTERY,
        DeviceField.LEFT_CHARGING,
        DeviceField.RIGHT_CHARGING,
    ),
    "02" to setOf(DeviceField.FIRMWARE), "04" to setOf(DeviceField.EQ), "05" to setOf(DeviceField.TOUCH_KEYS),
    "08" to setOf(DeviceField.GAME_MODE), "09" to setOf(DeviceField.EAR_DETECTION),
    "0a" to setOf(DeviceField.PROMPT_LANGUAGE), "0b" to setOf(DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT),
    "0c" to setOf(DeviceField.NOISE_MODE), "11" to setOf(DeviceField.ANC_LEVEL),
    "12" to setOf(DeviceField.TRANSPARENCY_LEVEL), "13" to setOf(DeviceField.PROMPT_VOLUME),
    "16" to setOf(DeviceField.TOUCH_ENABLED),
)

internal class F8Codec(override val profile: ModelProfile) : ProtocolCodec {
    private var buffer = byteArrayOf()

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 6) { "F8 queries require a 6-byte write capacity" }
        val tags = listOf(1, 12, 4, 8, 11, 2, 5, 9, 19, 17, 22) + if (profile.spec.f8ExtendedInit) listOf(10) else emptyList()
        return tags.map(::query)
    }

    override fun prepareSetting(setting: DeviceSetting, value: FieldValue, firmware: FieldValue.Firmware?): ProtocolSetting {
        val capabilities = profile.capabilities(firmware)
        if (setting !in capabilities.writableSettings) throw UnsupportedOperationException("该型号不支持此设置")
        val (command, tag, payload) = settingFrame(profile, setting, value, capabilities)
        val expected = if (setting == DeviceSetting.AUDIO_MODE || setting == DeviceSetting.MULTIPOINT) {
            val wire = payload.single()
            mapOf(
                DeviceField.AUDIO_MODE to FieldValue.Text(if (wire == 1) "ldac" else "aac_sbc"),
                DeviceField.MULTIPOINT to FieldValue.Toggle(wire == 0),
            )
        } else {
            mapOf(f8Fields.getValue(tag.toString(16).padStart(2, '0')).single() to value)
        }
        return ProtocolSetting(listOf(frame(command, *payload)), query(tag), expected)
    }

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> {
        buffer += bytes
        val replies = mutableListOf<ProtocolReply>()
        while (buffer.size >= 5) {
            if (buffer[1].u() !in listOf(0x27, 0x28) || buffer[2].u() != 1 || buffer[3].u() != 0) {
                buffer = buffer.copyOfRange(1, buffer.size)
            } else {
                val size = 5 + buffer[4].u()
                if (buffer.size < size) break
                val raw = buffer.copyOfRange(0, size)
                buffer = buffer.copyOfRange(size, buffer.size)
                replies += decode(raw)
            }
        }
        return replies
    }

    private fun decode(raw: ByteArray): ProtocolReply {
        if (raw.size < 6) return ProtocolReply(raw.hex())
        val tag = raw[5].u()
        val key = tag.toString(16).padStart(2, '0')
        if (key !in f8Fields) return ProtocolReply(raw.hex())
        if (raw.size <= 7) return ProtocolReply(raw.hex(), setOf(key), warnings = listOf("$key 字段被截断"))
        val fields = linkedMapOf<DeviceField, FieldReading>()
        decodeBasic(tag, raw, fields)
        if (profile.spec.f8ExtendedInit) decodeExtended(tag, key, raw, fields)
        return ProtocolReply(raw.hex(), setOf(key), fields)
    }

    private fun decodeBasic(tag: Int, raw: ByteArray, fields: MutableMap<DeviceField, FieldReading>) {
        val value = raw[7].u()
        when (tag) {
            1 -> decodeBattery(raw, fields)

            2 -> if (raw.size >= 11) {
                val version = raw[9].u() * 256 + raw[10].u()
                fields.putReading(raw, DeviceField.FIRMWARE, FieldValue.Firmware(listOf(version), version.toString()), 9)
            }

            4 -> {
                val name = when (value) {
                    0 -> "hifi"
                    1 -> "pop"
                    2 -> if (profile.spec.f8CollapsedEqRead) null else "rock"
                    else -> null
                }
                fields.putReading(raw, DeviceField.EQ, name?.let(FieldValue::Text))
            }

            5 -> decodeTouch(raw, fields)

            8 -> fields.putReading(raw, DeviceField.GAME_MODE, toggleValue(value))
        }
    }

    private fun decodeBattery(raw: ByteArray, fields: MutableMap<DeviceField, FieldReading>) {
        if (raw.size < 10) return
        val batteryFields = listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)
        batteryFields.forEachIndexed { index, field ->
            if (index != 2 || profile.batteryLayout == BatteryLayout.EARBUDS_AND_CASE) {
                val level = raw[7 + index].u() and 127
                fields.putReading(raw, field, if (level <= 100) FieldValue.Number(level) else null, 7 + index)
            }
        }
        fields.putReading(raw, DeviceField.LEFT_CHARGING, FieldValue.Toggle(raw[7].u() and 128 != 0), 7)
        fields.putReading(raw, DeviceField.RIGHT_CHARGING, FieldValue.Toggle(raw[8].u() and 128 != 0), 8)
    }

    private fun decodeTouch(raw: ByteArray, fields: MutableMap<DeviceField, FieldReading>) {
        val offsets = if (profile.spec.f8ExtendedInit) {
            listOf(9, 15, 21, 27, 12, 18, 24, 30)
        } else {
            listOf(7, 9, 11, 8, 10, 12)
        }
        if (raw.size <= offsets.max()) return
        val gestures = if (offsets.size == 8) {
            listOf("single", "double", "triple", "long")
        } else {
            listOf("single", "double", "long")
        }
        val bindings = offsets.mapIndexed { i, offset ->
            val action = raw[offset].u() and 15
            TouchBinding(if (i < gestures.size) "left" else "right", gestures[i % gestures.size], f8TouchActions[action], action)
        }
        fields.putReading(raw, DeviceField.TOUCH_KEYS, FieldValue.TouchKeys(bindings))
    }

    private fun decodeExtended(tag: Int, key: String, raw: ByteArray, fields: MutableMap<DeviceField, FieldReading>) {
        val value = raw[7].u()
        when (tag) {
            9 -> fields.putReading(raw, DeviceField.EAR_DETECTION, toggleValue(value))

            22 -> if (profile.spec.f8NoiseNormalWire == 0) {
                fields.putReading(raw, DeviceField.TOUCH_ENABLED, toggleValue(value))
            }

            10 -> {
                val language = when (value) {
                    0 -> "chinese"
                    1 -> "english"
                    else -> null
                }
                fields.putReading(raw, DeviceField.PROMPT_LANGUAGE, language?.let(FieldValue::Text))
            }

            11 -> decodeAudio(raw, fields)

            12 -> decodeNoise(raw, fields)

            17, 18 -> decodeNoiseLevel(profile, tag, key, raw, fields)

            19 -> if (value <= 9) fields.putReading(raw, DeviceField.PROMPT_VOLUME, FieldValue.Number(value))
        }
    }

    private fun decodeAudio(raw: ByteArray, fields: MutableMap<DeviceField, FieldReading>) {
        val value = raw[7].u()
        val mode = when (value) {
            0, 2 -> "aac_sbc"
            1 -> "ldac"
            else -> null
        }
        fields.putReading(raw, DeviceField.AUDIO_MODE, mode?.let(FieldValue::Text))
        fields.putReading(raw, DeviceField.MULTIPOINT, if (value in 0..2) FieldValue.Toggle(value == 0) else null)
    }

    private fun decodeNoise(raw: ByteArray, fields: MutableMap<DeviceField, FieldReading>) {
        val mode = when (raw[7].u()) {
            0 -> if (profile.spec.f8NoiseNormalWire == 0) "normal" else "wind"
            1 -> "anc"
            2 -> "transparency"
            6 -> if (profile.spec.f8NoiseNormalWire == 3) "normal" else null
            else -> null
        }
        fields.putReading(raw, DeviceField.NOISE_MODE, mode?.let(FieldValue::Text))
    }

    override fun reset() {
        buffer = byteArrayOf()
    }
}
private fun frame(command: Int, vararg data: Int): ByteArray {
    val header = byteArrayOf(0, command.toByte(), 1, 0, data.size.toByte())
    return header + data.map(Int::toByte).toByteArray()
}

private fun query(tag: Int): ProtocolQuery {
    val key = tag.toString(16).padStart(2, '0')
    val bytes = frame(0x27, tag)
    if (tag == 5) bytes[0] = 2
    return ProtocolQuery(key, bytes, setOf(key), f8Fields.getValue(key))
}
private fun MutableMap<DeviceField, FieldReading>.putReading(
    raw: ByteArray,
    field: DeviceField,
    value: FieldValue?,
    index: Int = 7,
) {
    val source = if (index < raw.size) byteArrayOf(raw[index]).hex() else ""
    this[field] = value?.let { FieldReading.Known(it, source) } ?: FieldReading.Unknown(source)
}
private fun toggleValue(value: Int): FieldValue? = if (value in 0..1) FieldValue.Toggle(value == 1) else null

/**
 * RoseLink's F8 receive handler maps the reduced wire level of the `11`/`12` replies to the UI level: 0/1/2 become
 * 5/3/1. The 10/6/2 immediates in the recovered class dump are tagged Dart Smis of the same three values, not levels
 * 10/6/2. Wire levels outside 0/1/2 stay undecoded; nothing here is confirmed against hardware.
 */
private fun noiseLevelValue(value: Int): FieldValue? = when (value) {
    0 -> FieldValue.Number(5)
    1 -> FieldValue.Number(3)
    2 -> FieldValue.Number(1)
    else -> null
}

// Only a model whose configuration offers the level control decodes it; the shared CERAMICS-X receiver
// keeps the 0x11/0x12 tags Unknown for models whose level flags are off (CERAMICS-X and 7034).
private fun decodeNoiseLevel(
    profile: ModelProfile,
    tag: Int,
    key: String,
    raw: ByteArray,
    fields: MutableMap<DeviceField, FieldReading>,
) {
    val setting = if (tag == 17) DeviceSetting.ANC_LEVEL else DeviceSetting.TRANSPARENCY_LEVEL
    val levels = profile.capabilities().noiseControl?.levelCapabilities(setting)
    fields.putReading(raw, f8Fields.getValue(key).single(), if (levels != null) noiseLevelValue(raw[7].u()) else null)
}

internal val f8TouchActions = mapOf(
    0 to "none",
    2 to "voice_assistant",
    3 to "previous_track",
    4 to "next_track",
    5 to "volume_up",
    6 to "volume_down",
    7 to "play_pause",
    9 to "noise_cycle",
)

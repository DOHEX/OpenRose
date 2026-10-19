package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.equalizerCapabilities
import io.github.dohex.openrose.core.gainLevels
import io.github.dohex.openrose.core.hearingProtectionLevels
import io.github.dohex.openrose.core.promptVolumeLevels

internal class I7Codec : ProtocolCodec {
    override val profile = ModelProfile.I7_RS_V2
    private var sequence = 0
    private var buffer = byteArrayOf()
    private val infoTypes = "010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649".bytes()

    override fun prepareSetting(setting: DeviceSetting, value: FieldValue, firmware: FieldValue.Firmware?): ProtocolSetting {
        if (setting == DeviceSetting.CUSTOM_EQ) {
            require(value is FieldValue.IntegerList) { "自定义均衡器需要十段整数曲线" }
            val gains = value.values.toList()
            require(gains.size == 10 && gains.all { it in -6..6 }) { "自定义均衡器需要十段 -6..6 的整数" }
            // Validate and snapshot the whole curve before allocating either write or read sequence.
            val payload = gains.map { if (it < 0) 0x80 or -it else it }.map { it.toByte() }.toByteArray()
            val writes = listOf(frame(byteArrayOf(0x2a, 4)), frame(byteArrayOf(0x3e) + payload))
            val modeReadback = query(byteArrayOf(0x2a))
            val curveReadback = query(byteArrayOf(0x3e))
            return ProtocolSetting(
                writes, modeReadback,
                mapOf(DeviceField.EQ to FieldValue.Text("custom"), DeviceField.CUSTOM_EQ to FieldValue.IntegerList(gains)),
                listOf(curveReadback),
            )
        }
        val expected = if (setting == DeviceSetting.NOISE_CYCLE) validatedNoiseCycle(value) else value
        val (type, payload) = when (setting) {
            DeviceSetting.NOISE_MODE -> {
                require(value is FieldValue.Text) { "降噪模式需要模式名称" }
                val mode = mapOf("anc" to 1, "normal" to 2, "transparency" to 3, "wind" to 4, "auto" to 5, "dynamic" to 7)[value.value]
                    ?: throw UnsupportedOperationException("i7 不支持该降噪模式")
                9.toByte() to byteArrayOf(mode.toByte())
            }
            DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL -> {
                require(value is FieldValue.Number) { "档位需要整数" }
                require(value.value in setOf(1, 3, 5)) { "i7 档位仅支持 1、3、5" }
                (if (setting == DeviceSetting.ANC_LEVEL) 0x2c else 0x2d).toByte() to byteArrayOf(value.value.toByte())
            }
            DeviceSetting.NOISE_CYCLE -> {
                val flags = (expected as FieldValue.NoiseCycle).flags
                0x36.toByte() to listOf("anc", "wind", "normal", "transparency").map { if (flags.getValue(it) == true) 1.toByte() else 0.toByte() }.toByteArray()
            }
            DeviceSetting.EQ -> {
                require(value is FieldValue.Text) { "均衡器需要预设名称" }
                if (value.value !in equalizerCapabilities(profile, firmware).presets) {
                    throw UnsupportedOperationException("i7 当前固件不支持该均衡器预设")
                }
                val preset = mapOf("hifi" to 0, "pop" to 1, "rock" to 2, "light" to 3, "eap" to 6).getValue(value.value)
                0x2a.toByte() to byteArrayOf(preset.toByte())
            }
            DeviceSetting.GAIN -> {
                require(value is FieldValue.Number) { "增益需要档位整数" }
                require(value.value in 0..2) { "i7 增益档位仅支持 0～2" }
                if (value.value !in gainLevels(profile, firmware)) {
                    throw UnsupportedOperationException("i7 当前固件不支持增益设置")
                }
                0x45.toByte() to byteArrayOf(value.value.toByte())
            }
            DeviceSetting.HEARING_PROTECTION -> {
                require(value is FieldValue.Number) { "听力保护需要档位整数" }
                require(value.value in 0..6) { "i7 听力保护档位仅支持 0～6" }
                if (value.value !in hearingProtectionLevels(profile, firmware)) {
                    throw UnsupportedOperationException("i7 当前固件不支持听力保护设置")
                }
                0x46.toByte() to byteArrayOf(value.value.toByte())
            }
            DeviceSetting.PROMPT_VOLUME -> {
                require(value is FieldValue.Number) { "提示音需要档位整数" }
                require(value.value in 0..5) { "i7 提示音仅支持 0～5 档" }
                if (value.value !in promptVolumeLevels(profile, firmware)) {
                    throw UnsupportedOperationException("i7 当前固件不支持此提示音档位")
                }
                0x2e.toByte() to byteArrayOf(value.value.toByte())
            }
            DeviceSetting.GAME_MODE -> {
                require(value is FieldValue.Toggle) { "游戏模式需要明确开关" }
                0x0e.toByte() to byteArrayOf(if (value.value) 1 else 0)
            }
            DeviceSetting.AUDIO_MODE -> {
                require(value is FieldValue.Text) { "音频模式需要配置名称" }
                val mode = listOf("aac_sbc", "ldac", "lhdc", "lc3").indexOf(value.value)
                if (mode < 0) throw UnsupportedOperationException("i7 不支持该音频配置")
                0x2b.toByte() to byteArrayOf(mode.toByte())
            }
            DeviceSetting.MULTIPOINT -> {
                require(value is FieldValue.Toggle) { "多点连接需要明确开关" }
                0x32.toByte() to byteArrayOf(if (value.value) 1 else 0)
            }
            DeviceSetting.TOUCH_ENABLED, DeviceSetting.EAR_DETECTION -> {
                require(value is FieldValue.Toggle) { "触控和检测功能需要明确开关" }
                (if (setting == DeviceSetting.TOUCH_ENABLED) 7 else 8).toByte() to byteArrayOf(if (value.value) 0 else 1)
            }
            DeviceSetting.TOUCH_BINDING -> {
                val binding = validatedTouchAssignment(value, profile)
                val gesture = listOf("single", "double", "triple", "long").indexOf(binding.gesture)
                val slot = gesture + 1 + if (binding.side == "right") 0x10 else 0
                val action = listOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "game_mode", "voice_assistant", "noise_cycle").indexOf(binding.action)
                1.toByte() to byteArrayOf(slot.toByte(), action.toByte())
            }
        }
        // Validation precedes both sequence allocations, so rejected settings consume no sequence.
        val key = byteArrayOf(type).hex()
        return ProtocolSetting(
            listOf(frame(byteArrayOf(type) + payload)),
            query(byteArrayOf(type)),
            mapOf(i7Fields.getValue(key).single() to expected),
        )
    }

    private fun frame(body: ByteArray): ByteArray {
        val prefix = byteArrayOf(0xff.toByte(), sequence.toByte(), body.size.toByte()) + body
        sequence = (sequence + 1) and 255
        return prefix + byteArrayOf((prefix.sumOf { it.u() } and 255).toByte(), 0xaa.toByte())
    }

    private fun query(ids: ByteArray): ProtocolQuery {
        val keys = ids.map { byteArrayOf(it).hex() }.filter { it in i7Fields }.toSet()
        return ProtocolQuery("fa" + ids.hex(), frame(byteArrayOf(0xfa.toByte()) + ids), keys, keys.flatMap { i7Fields.getValue(it) }.toSet())
    }

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 7) { "i7 queries require at least a 7-byte write capacity" }
        val groups = infoTypes.toList().chunked(maxWriteSize - 6)
        return (groups + listOf(listOf(0x3e.toByte()))).map { query(it.toByteArray()) }
    }

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> {
        buffer += bytes
        val replies = mutableListOf<ProtocolReply>()
        if (buffer.size > 8192) {
            replies += ProtocolReply(buffer.hex(), warnings = listOf("接收缓冲区超过 8192 字节，已清空"))
            buffer = byteArrayOf()
            return replies
        }
        while (buffer.isNotEmpty()) {
            val start = buffer.indexOf(0xdd.toByte())
            if (start < 0) {
                replies += ProtocolReply(buffer.hex(), warnings = listOf("没有 DD 接收帧头"))
                buffer = byteArrayOf()
                break
            }
            if (start > 0) {
                replies += ProtocolReply(buffer.copyOfRange(0, start).hex(), warnings = listOf("忽略帧前数据"))
                buffer = buffer.copyOfRange(start, buffer.size)
            }
            val complete = findComplete(0)
            if (complete != null) {
                val (end, reply) = complete
                replies += reply
                buffer = buffer.copyOfRange(end, buffer.size)
                continue
            }
            // There is no total frame length. A later DD may be opaque bytes inside an
            // incomplete declared V, even if those bytes resemble a checksummed frame.
            // Retain the leading frame until complete; reset/buffer limit handles corruption.
            break
        }
        return replies
    }

    private fun findComplete(start: Int): Pair<Int, ProtocolReply>? {
        for (end in start + 5 until buffer.size) {
            if (buffer[end].u() != 0xaa) continue
            val raw = buffer.copyOfRange(start, end + 1)
            val reply = parseFrame(raw) ?: continue
            return (end + 1) to reply
        }
        return null
    }

    private fun parseFrame(raw: ByteArray): ProtocolReply? {
        val checksum = raw[raw.size - 2].u()
        val prefixMatches = (raw.dropLast(2).sumOf { it.u() } and 255) == checksum
        val recordsMatch = (raw.slice(2 until raw.size - 2).sumOf { it.u() } and 255) == checksum
        if (!prefixMatches && !recordsMatch) return null
        val readings = linkedMapOf<DeviceField, FieldReading>()
        val keys = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        var position = 2
        val stop = raw.size - 2
        while (position < stop) {
            if (stop - position < 2) return null
            val length = raw[position].u()
            val end = if (length == 0) stop else position + length + 1
            if (end > stop) return null
            val type = raw[position + 1].u()
            val value = raw.copyOfRange(position + 2, end)
            if (length == 0) warnings += "L00 兼容字段；客户端仍验证校验字节"
            val decoded = decodeRecord(type, value)
            if (decoded.isNotEmpty()) {
                keys += byteArrayOf(type.toByte()).hex()
                readings += decoded
            } else {
                warnings += "未更新字段 ${byteArrayOf(type.toByte()).hex()}=${value.hex()}"
            }
            position = end
        }
        return ProtocolReply(raw.hex(), keys, readings, warnings)
    }

    private fun decodeRecord(type: Int, value: ByteArray): Map<DeviceField, FieldReading> {
        val key = byteArrayOf(type.toByte()).hex()
        val expected = i7Fields[key] ?: return emptyMap()
        val readings = linkedMapOf<DeviceField, FieldReading>()
        fun put(field: DeviceField, semantic: FieldValue?, source: ByteArray = value) {
            readings[field] = semantic?.let { FieldReading.Known(it, source.hex()) } ?: FieldReading.Unknown(source.hex())
        }
        val raw = value.firstOrNull()?.u()
        when (type) {
            0x0c -> {
                if (value.size < 3) return emptyMap()
                val left = value[0].u()
                val right = value[1].u()
                val case = value[2].u()
                val caseAvailable = (case and 127) in 1..100 &&
                    ((left and 128 != 0 && left != 255) || (right and 128 != 0 && right != 255))
                for ((index, field) in listOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY).withIndex()) {
                    val byte = value[index].u()
                    readings[field] = when {
                        byte == 255 -> FieldReading.NotProvided(byteArrayOf(value[index]).hex())
                        index == 2 && !caseAvailable -> FieldReading.Unknown(byteArrayOf(value[index]).hex())
                        byte and 127 in 1..100 -> FieldReading.Known(FieldValue.Number(byte and 127), byteArrayOf(value[index]).hex())
                        else -> FieldReading.Unknown(byteArrayOf(value[index]).hex())
                    }
                }
                listOf(DeviceField.LEFT_IN_CASE, DeviceField.RIGHT_IN_CASE, DeviceField.CASE_FLAG).forEachIndexed { index, field ->
                    put(field, if (index < 2 && value[index].u() == 255) null else FieldValue.Toggle(value[index].u() and 128 != 0), byteArrayOf(value[index]))
                }
            }
            0x0d -> {
                if (value.size < 3) return emptyMap()
                val parts = value.take(3).map { it.u() }
                put(DeviceField.FIRMWARE, FieldValue.Firmware(parts, parts.joinToString("")))
            }
            0x01 -> {
                if (value.size < 16 || value.size % 2 != 0) return emptyMap()
                val pairs = value.toList().chunked(2).associate { it[0].u() to it[1].u() }
                val slots = listOf(1, 2, 3, 4, 0x11, 0x12, 0x13, 0x14)
                if (!slots.all { it in pairs }) return emptyMap()
                val actions = listOf("none", "play_pause", "previous_track", "next_track", "volume_up", "volume_down", "game_mode", "voice_assistant", "noise_cycle")
                val gestures = listOf("single", "double", "triple", "long")
                val bindings = slots.mapIndexed { index, slot ->
                    val action = pairs.getValue(slot)
                    TouchBinding(if (index < 4) "left" else "right", gestures[index % 4], actions.getOrNull(action), action)
                }
                put(DeviceField.TOUCH_KEYS, FieldValue.TouchKeys(bindings))
            }
            0x09 -> {
                if (value.size != 1) return emptyMap()
                put(DeviceField.NOISE_MODE, mapOf(1 to "anc", 2 to "normal", 3 to "transparency", 4 to "wind", 5 to "auto", 6 to "deep", 7 to "dynamic")[raw]?.let { FieldValue.Text(it) })
            }
            0x07, 0x08, 0x0e, 0x32 -> {
                if (value.size != 1) return emptyMap()
                val on = if (type in listOf(7, 8)) 0 else 1
                put(expected.single(), if (raw in 0..1) FieldValue.Toggle(raw == on) else null)
            }
            0x2a -> {
                if (value.size !in 1..2) return emptyMap()
                val index = if (value.size == 1) when (raw) { 0 -> 1; 1 -> 0; else -> raw } else raw
                put(DeviceField.EQ, mapOf(0 to "pop", 1 to "hifi", 2 to "rock", 3 to "light", 4 to "custom", 5 to "custom_slot", 6 to "eap")[index]?.let { FieldValue.Text(it) })
                if (value.size == 2 && raw == 5) put(DeviceField.EQ_SLOT, FieldValue.Number(value[1].u()))
            }
            0x2b, 0x2c, 0x2d, 0x2e -> {
                if (value.isEmpty()) return emptyMap()
                // Observed F8 helpers parse the entire hex text with decimal radix.
                val decimalText = value.hex()
                val number = if (decimalText.all { it in '0'..'9' }) decimalText.toIntOrNull() else null
                val semantic = when (type) {
                    0x2b -> number?.let { listOf("aac_sbc", "ldac", "lhdc", "lc3").getOrNull(it) }?.let { FieldValue.Text(it) }
                    0x2c, 0x2d -> number?.takeIf { it in listOf(1, 3, 5) }?.let { FieldValue.Number(it) }
                    else -> number?.takeIf { it in 0..5 }?.let { FieldValue.Number(it) }
                }
                put(expected.single(), semantic)
            }
            0x36 -> {
                if (value.size < 4) return emptyMap()
                val flags = listOf("anc", "wind", "normal", "transparency").mapIndexed { index, name ->
                    val flag = value[index].u()
                    name to if (flag in 0..1) flag == 1 else null
                }.toMap()
                put(DeviceField.NOISE_CYCLE, FieldValue.NoiseCycle(flags))
            }
            0x3e -> {
                val gains = value.map { if (it.u() and 128 != 0) -(it.u() and 127) else it.u() and 127 }
                put(DeviceField.CUSTOM_EQ, gains.takeIf { it.size == 10 && it.all { gain -> gain in -6..6 } }?.let { FieldValue.IntegerList(it) })
            }
            0x45, 0x46 -> {
                if (value.size != 1) return emptyMap()
                val max = if (type == 0x45) 2 else 6
                put(expected.single(), raw?.takeIf { it in 0..max }?.let { FieldValue.Number(it) })
            }
            0x2f -> {
                if (value.size != 1) return emptyMap()
                put(DeviceField.FIND_STATE, mapOf(1 to "start_left", 2 to "start_right", 3 to "start_both", 4 to "stop_both", 5 to "stop_left", 6 to "stop_right")[raw]?.let { FieldValue.Text(it) })
            }
        }
        return readings
    }

    override fun reset() {
        sequence = 0
        buffer = byteArrayOf()
    }
}

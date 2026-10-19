package io.github.dohex.openrose.core.protocol

internal class I7Codec : ProtocolCodec {
    override val profile = ModelProfile.I7_RS_V2
    private var sequence = 0
    private var buffer = byteArrayOf()
    private val infoTypes = "010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649".bytes()

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 7) { "i7 queries require at least a 7-byte write capacity" }
        val groups = infoTypes.toList().chunked(maxWriteSize - 6)
        return (groups + listOf(listOf(0x3e.toByte()))).map { ids ->
            val body = byteArrayOf(0xfa.toByte()) + ids.toByteArray()
            val prefix = byteArrayOf(0xff.toByte(), sequence.toByte(), body.size.toByte()) + body
            val packet = prefix + byteArrayOf((prefix.sumOf { it.u() } and 255).toByte(), 0xaa.toByte())
            sequence = (sequence + 1) and 255
            val keys = ids.map { byteArrayOf(it).hex() }.filter { it in i7Fields }.toSet()
            ProtocolQuery("fa" + ids.toByteArray().hex(), packet, keys, keys.flatMap { i7Fields.getValue(it) }.toSet())
        }
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

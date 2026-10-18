package io.github.dohex.openrose.core.protocol

internal class I5Codec : ProtocolCodec {
    override val profile = ModelProfile.I5_ZT

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 10) { "i5 queries require a 10-byte write capacity" }
        return i5Fields.map { (key, fields) ->
            val prefix = "08ee000000${key}0a00".bytes()
            val packet = prefix + byteArrayOf((prefix.sumOf { it.u() } and 255).toByte())
            ProtocolQuery(key, packet, setOf(key), fields)
        }
    }

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> {
        val raw = bytes.copyOf()
        if (raw.size < 7) return listOf(ProtocolReply(raw.hex(), warnings = listOf("通知短于命令字段")))
        val command = raw.copyOfRange(5, 7).hex()
        val minimum = when (command) {
            "0101" -> 16
            "0d01", "0605" -> 13
            "0401" -> 17
            "0602" -> 14
            in i5Fields -> 10
            else -> return listOf(ProtocolReply(raw.hex(), warnings = listOf("未知字段 $command")))
        }
        if (raw.size < minimum) return listOf(ProtocolReply(raw.hex(), warnings = listOf("$command 字段被截断")))
        val fields = linkedMapOf<DeviceField, FieldReading>()
        fun known(field: DeviceField, value: FieldValue, bytes: ByteArray = raw.copyOfRange(9, raw.size)) {
            fields[field] = FieldReading.Known(value, bytes.hex())
        }
        fun choice(field: DeviceField, choices: List<String>, nibble: Boolean = true) {
            val value = if (nibble) raw[9].u() and 15 else raw[9].u()
            fields[field] = choices.getOrNull(value)?.let { FieldReading.Known(FieldValue.Text(it), byteArrayOf(raw[9]).hex()) }
                ?: FieldReading.Unknown(byteArrayOf(raw[9]).hex())
        }
        val nibble = raw[9].u() and 15
        when (command) {
            "0101" -> {
                for ((field, offset) in listOf(DeviceField.LEFT_BATTERY to 11, DeviceField.RIGHT_BATTERY to 12, DeviceField.CASE_BATTERY to 15)) {
                    val value = raw[offset].u()
                    val hex = byteArrayOf(raw[offset]).hex()
                    fields[field] = when {
                        value == 255 -> FieldReading.NotProvided(hex)
                        field == DeviceField.CASE_BATTERY && raw[13].u() == 0 && raw[14].u() == 0 -> FieldReading.Unknown(hex)
                        value in 1..100 -> FieldReading.Known(FieldValue.Number(value), hex)
                        else -> FieldReading.Unknown(hex)
                    }
                }
                for ((field, offset) in listOf(DeviceField.LEFT_CHARGING to 13, DeviceField.RIGHT_CHARGING to 14)) {
                    known(field, FieldValue.Toggle(raw[offset].u() != 0), byteArrayOf(raw[offset]))
                }
            }
            "0d01" -> {
                val parts = (9..11).map { raw[it].u() and 15 }
                known(DeviceField.FIRMWARE, FieldValue.Firmware(parts, parts.joinToString("")), raw.copyOfRange(9, 12))
            }
            "0401" -> {
                val actions = listOf("volume_up", "volume_down", "previous_track", "next_track", "voice_assistant", "play_pause", "none", "noise_cycle")
                val gestures = listOf("single", "double", "triple", "long")
                val bindings = (0..7).map {
                    val action = raw[9 + it].u() and 15
                    TouchBinding(if (it < 4) "left" else "right", gestures[it % 4], actions.getOrNull(action), action)
                }
                known(DeviceField.TOUCH_KEYS, FieldValue.TouchKeys(bindings), raw.copyOfRange(9, 17))
            }
            "0602" -> {
                val tail = raw.copyOfRange(9, raw.size).hex()
                val mode = mapOf("0100000020" to "anc", "0001000020" to "normal", "0000010020" to "wind", "0000000120" to "transparency", "0200000021" to "auto")[tail]
                fields[DeviceField.NOISE_MODE] = mode?.let { FieldReading.Known(FieldValue.Text(it), tail) } ?: FieldReading.Unknown(tail)
            }
            "0605" -> {
                val names = listOf("normal", "transparency", "wind", "anc")
                val flags = names.mapIndexed { index, name ->
                    val flag = raw[9 + index].u() and 15
                    name to if (flag in 0..1) flag == 1 else null
                }.toMap()
                known(DeviceField.NOISE_CYCLE, FieldValue.NoiseCycle(flags), raw.copyOfRange(9, 13))
            }
            "0603", "0c01", "0a01" -> {
                val field = i5Fields.getValue(command).single()
                fields[field] = if (nibble in 0..1) FieldReading.Known(FieldValue.Toggle(nibble == 0), byteArrayOf(raw[9]).hex())
                    else FieldReading.Unknown(byteArrayOf(raw[9]).hex())
            }
            "0201" -> choice(DeviceField.EQ, listOf("pop", "hifi", "rock", "light"))
            "0801" -> {
                choice(DeviceField.AUDIO_MODE, listOf("aac_sbc", "ldac"))
                fields[DeviceField.MULTIPOINT] = if (nibble in 0..1) FieldReading.Known(FieldValue.Toggle(nibble == 0), byteArrayOf(raw[9]).hex())
                    else FieldReading.Unknown(byteArrayOf(raw[9]).hex())
            }
            "0607", "0604" -> {
                val field = i5Fields.getValue(command).single()
                val level = listOf(1, 3, 5).getOrNull(raw[9].u())
                fields[field] = level?.let { FieldReading.Known(FieldValue.Number(it), byteArrayOf(raw[9]).hex()) }
                    ?: FieldReading.Unknown(byteArrayOf(raw[9]).hex())
            }
            "0e03" -> {
                fields[DeviceField.PROMPT_VOLUME] = if (nibble in 0..4) FieldReading.Known(FieldValue.Number(nibble + 1), byteArrayOf(raw[9]).hex())
                    else FieldReading.Unknown(byteArrayOf(raw[9]).hex())
            }
        }
        return listOf(ProtocolReply(raw.hex(), setOf(command), fields))
    }

    override fun reset() = Unit
}

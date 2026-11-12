package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting

/** ZT family frames: 08EE requests, fixed command keys and nibble-encoded replies. */
internal class ZtCodec(override val profile: ModelProfile) : ProtocolCodec {
    override fun prepareSetting(setting: DeviceSetting, value: FieldValue, firmware: FieldValue.Firmware?): ProtocolSetting {
        val expected = if (setting == DeviceSetting.NOISE_CYCLE) validatedNoiseCycle(value) else value
        val (writeKey, readKey, payload) = when (setting) {
            DeviceSetting.NOISE_MODE -> {
                require(value is FieldValue.Text) { "降噪模式需要模式名称" }
                val modes = if (profile.spec.ztThreeByteNoise) {
                    mapOf("anc" to "010000", "normal" to "000001", "transparency" to "000100")
                } else {
                    mapOf("anc" to "01000000", "normal" to "00010000", "wind" to "00000100", "transparency" to "00000001")
                }
                val mode = modes[value.value]
                    ?: throw UnsupportedOperationException("该型号不支持该降噪模式")
                Triple("0682", "0602", mode.bytes())
            }

            DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL -> {
                require(value is FieldValue.Number) { "档位需要整数" }
                val level = listOf(1, 3, 5).indexOf(value.value)
                require(level >= 0) { "ZT 档位仅支持 1、3、5" }
                val wire = (level + profile.spec.ztLevelWriteOffset).toByte()
                if (setting == DeviceSetting.ANC_LEVEL) {
                    Triple("0687", "0607", byteArrayOf(wire))
                } else {
                    Triple("0684", "0604", byteArrayOf(wire))
                }
            }

            DeviceSetting.NOISE_CYCLE -> {
                val flags = (expected as FieldValue.NoiseCycle).flags
                val payload = listOf("normal", "transparency", "wind", "anc")
                    .map { if (flags.getValue(it) == true) 1.toByte() else 0.toByte() }
                    .toByteArray()
                Triple("0685", "0605", payload)
            }

            DeviceSetting.EQ -> {
                require(value is FieldValue.Text) { "均衡器需要预设名称" }
                val preset = eqPresets.indexOf(value.value)
                if (preset < 0 || value.value !in profile.capabilities(firmware).equalizer?.presets.orEmpty()) {
                    throw UnsupportedOperationException("该型号不支持该均衡器预设")
                }
                Triple("0281", "0201", byteArrayOf(preset.toByte()))
            }

            DeviceSetting.GAME_MODE -> {
                require(value is FieldValue.Toggle) { "游戏模式需要明确开关" }
                Triple("0683", "0603", byteArrayOf(if (value.value) 0 else 1))
            }

            DeviceSetting.AUDIO_MODE -> {
                require(value is FieldValue.Text) { "音频模式需要配置名称" }
                val modes = if (profile.spec.ztIndependentMultipoint) {
                    listOf("aac_sbc", "ldac", "lhdc", "ldac_lhdc")
                } else {
                    listOf("aac_sbc", "ldac")
                }
                val mode = modes.indexOf(value.value)
                if (mode < 0) throw UnsupportedOperationException("该型号不支持该音频配置")
                Triple("0881", "0801", byteArrayOf(mode.toByte()))
            }

            DeviceSetting.MULTIPOINT -> {
                require(value is FieldValue.Toggle) { "多点连接需要明确开关" }
                if (profile.spec.ztIndependentMultipoint) {
                    Triple("0882", "0802", byteArrayOf(if (value.value) 1 else 0))
                } else {
                    Triple("0881", "0801", byteArrayOf(if (value.value) 0 else 1))
                }
            }

            DeviceSetting.TOUCH_ENABLED, DeviceSetting.EAR_DETECTION -> {
                require(value is FieldValue.Toggle) { "触控和检测功能需要明确开关" }
                val payload = byteArrayOf(if (value.value) 0 else 1)
                if (setting == DeviceSetting.TOUCH_ENABLED) {
                    Triple("0a81", "0a01", payload)
                } else {
                    Triple("0c81", "0c01", payload)
                }
            }

            DeviceSetting.TOUCH_BINDING -> {
                val binding = validatedTouchAssignment(value, profile)
                val side = if (binding.side == "left") 0 else 1
                val gesture = listOf("single", "double", "triple", "long").indexOf(binding.gesture)
                val action = listOf(
                    "volume_up",
                    "volume_down",
                    "previous_track",
                    "next_track",
                    "voice_assistant",
                    "play_pause",
                    "none",
                    "noise_cycle",
                ).indexOf(binding.action)
                Triple("0481", "0401", byteArrayOf(side.toByte(), gesture.toByte(), action.toByte()))
            }

            DeviceSetting.PROMPT_VOLUME -> {
                require(value is FieldValue.Number && value.value in ProtocolFamily.ZT.promptVolumeLevels) { "ZT 提示音仅支持 1～5 档" }
                if (profile.spec.ztPromptVolumeDirect) {
                    Triple("0e03", "0e03", byteArrayOf(value.value.toByte()))
                } else {
                    Triple("0e83", "0e03", byteArrayOf((value.value - 1).toByte()))
                }
            }

            DeviceSetting.CUSTOM_EQ -> {
                require(value is FieldValue.IntegerList) { "自定义均衡器需要整数曲线" }
                val custom = profile.capabilities(firmware).equalizer?.custom
                    ?: throw UnsupportedOperationException("该型号不支持自定义均衡器")
                val gains = value.values.toList()
                require(gains.size == custom.bandsHz.size && gains.all { it in custom.minGain..custom.maxGain }) {
                    "自定义均衡器需要 ${custom.bandsHz.size} 段 ${custom.minGain}..${custom.maxGain} 的整数"
                }
                val slot = eqPresets.indexOf("custom")
                require(slot >= 0) { "该型号的自定义均衡器没有可选的预设槽位" }
                // Validate and snapshot the whole curve before allocating either write frame.
                val payload = gains.map { if (it < 0) 0x80 or -it else it }.map { it.toByte() }.toByteArray()
                return ProtocolSetting(
                    listOf(frame("0281", byteArrayOf(slot.toByte())), frame("0282", payload)),
                    query("0201"),
                    mapOf(DeviceField.EQ to FieldValue.Text("custom"), DeviceField.CUSTOM_EQ to FieldValue.IntegerList(gains)),
                    listOf(query("0202")),
                )
            }

            DeviceSetting.GAIN -> {
                require(value is FieldValue.Number && value.value in profile.capabilities(firmware).gainLevels) { "该型号不支持此增益档位" }
                Triple("0283", "0203", byteArrayOf((value.value + 1).toByte()))
            }

            else -> throw UnsupportedOperationException("该型号不支持此设置")
        }
        val expectedValues = if (readKey == "0801" && !profile.spec.ztIndependentMultipoint) {
            val multipoint = payload.single().u() == 0
            mapOf(
                DeviceField.AUDIO_MODE to FieldValue.Text(if (multipoint) "aac_sbc" else "ldac"),
                DeviceField.MULTIPOINT to FieldValue.Toggle(multipoint),
            )
        } else {
            mapOf(query(readKey).expectedFields.single() to expected)
        }
        return ProtocolSetting(
            listOf(frame(writeKey, payload)),
            query(readKey),
            expectedValues,
        )
    }

    /** ZT models with independent multipoint carry the custom curve in slot 3 instead of the light preset. */
    private val eqPresets: List<String>
        get() = if (profile.spec.ztIndependentMultipoint) {
            listOf("pop", "hifi", "rock", "custom")
        } else {
            listOf("pop", "hifi", "rock", "light")
        }

    private fun frame(key: String, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = payload.size + 10
        val prefix = "08ee000000$key".bytes() + byteArrayOf(length.toByte(), (length shr 8).toByte()) + payload
        return prefix + byteArrayOf((prefix.sumOf { it.u() } and 255).toByte())
    }

    private fun query(key: String): ProtocolQuery {
        val fields = when {
            key == "0101" && profile.batteryLayout == BatteryLayout.SINGLE_UNIT -> emptySet()
            key == "0801" && profile.spec.ztIndependentMultipoint -> setOf(DeviceField.AUDIO_MODE)
            else -> ztFields.getValue(key)
        }
        val responseKeys = if (key == "0101" && profile.batteryLayout == BatteryLayout.SINGLE_UNIT) emptySet() else setOf(key)
        return ProtocolQuery(key, frame(key), responseKeys, fields)
    }

    override fun initQueries(maxWriteSize: Int): List<ProtocolQuery> {
        require(maxWriteSize >= 10) { "ZT queries require a 10-byte write capacity" }
        val keys = profile.spec.ztInitKeys.ifEmpty {
            listOf("0101", "0602", "0201", "0603", "0801", "0d01", "0401", "0a01", "0605", "0c01", "0607", "0604", "0e03")
        }
        return keys.map(::query)
    }

    override fun feedNotification(bytes: ByteArray): List<ProtocolReply> {
        val raw = bytes.copyOf()
        if (raw.size < 7) return listOf(ProtocolReply(raw.hex(), warnings = listOf("通知短于命令字段")))
        val command = raw.copyOfRange(5, 7).hex()
        val minimum = when (command) {
            "0101" -> 16
            "0202" -> 19
            "0d01", "0605" -> 13
            "0401" -> 17
            "0602" -> 14
            in ztFields -> 10
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
                if (profile.batteryLayout == BatteryLayout.SINGLE_UNIT) return listOf(ProtocolReply(raw.hex(), setOf(command)))
                val batteries = listOf(
                    DeviceField.LEFT_BATTERY to 11,
                    DeviceField.RIGHT_BATTERY to 12,
                    DeviceField.CASE_BATTERY to 15,
                )
                for ((field, offset) in batteries) {
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

            "0103" -> {
                if (profile.batteryLayout == BatteryLayout.SINGLE_UNIT) {
                    val battery = raw[9].u()
                    val hex = byteArrayOf(raw[9]).hex()
                    fields[DeviceField.CASE_BATTERY] = when {
                        battery == 255 -> FieldReading.NotProvided(hex)
                        battery in 1..100 -> FieldReading.Known(FieldValue.Number(battery), hex)
                        else -> FieldReading.Unknown(hex)
                    }
                }
            }

            "0202" -> {
                val gains = (9..18).map { offset ->
                    val encoded = raw[offset].u()
                    if (encoded and 0x80 != 0) -(encoded and 0x7f) else encoded
                }
                known(DeviceField.CUSTOM_EQ, FieldValue.IntegerList(gains), raw.copyOfRange(9, 19))
            }

            "0203" -> {
                val gain = raw[9].u()
                fields[DeviceField.GAIN] = if (gain in 1..3) {
                    FieldReading.Known(FieldValue.Number(gain - 1), byteArrayOf(raw[9]).hex())
                } else {
                    FieldReading.Unknown(byteArrayOf(raw[9]).hex())
                }
            }

            "0d01" -> {
                val parts = (9..11).map { raw[it].u() and 15 }
                known(DeviceField.FIRMWARE, FieldValue.Firmware(parts, parts.joinToString("")), raw.copyOfRange(9, 12))
            }

            "0401" -> {
                val actions = listOf(
                    "volume_up",
                    "volume_down",
                    "previous_track",
                    "next_track",
                    "voice_assistant",
                    "play_pause",
                    "none",
                    "noise_cycle",
                )
                val gestures = listOf("single", "double", "triple", "long")
                val bindings = (0..7).map {
                    val action = raw[9 + it].u() and 15
                    TouchBinding(if (it < 4) "left" else "right", gestures[it % 4], actions.getOrNull(action), action)
                }
                known(DeviceField.TOUCH_KEYS, FieldValue.TouchKeys(bindings), raw.copyOfRange(9, 17))
            }

            "0602" -> {
                val tail = raw.copyOfRange(9, raw.size).hex()
                val mode = mapOf(
                    "0100000020" to "anc",
                    "0001000020" to "normal",
                    "0000010020" to "wind",
                    "0000000120" to "transparency",
                    "0200000021" to "auto",
                )[tail]
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
                val field = ztFields.getValue(command).single()
                fields[field] = if (nibble in 0..1) {
                    FieldReading.Known(FieldValue.Toggle(nibble == 0), byteArrayOf(raw[9]).hex())
                } else {
                    FieldReading.Unknown(byteArrayOf(raw[9]).hex())
                }
            }

            "0201" -> choice(DeviceField.EQ, eqPresets)

            "0801" -> {
                val modes = if (profile.spec.ztIndependentMultipoint) {
                    listOf("aac_sbc", "ldac", "lhdc", "ldac_lhdc")
                } else {
                    listOf("aac_sbc", "ldac")
                }
                choice(DeviceField.AUDIO_MODE, modes)
                if (!profile.spec.ztIndependentMultipoint) {
                    fields[DeviceField.MULTIPOINT] = if (nibble in 0..1) {
                        FieldReading.Known(FieldValue.Toggle(nibble == 0), byteArrayOf(raw[9]).hex())
                    } else {
                        FieldReading.Unknown(byteArrayOf(raw[9]).hex())
                    }
                }
            }

            "0802" -> {
                fields[DeviceField.MULTIPOINT] = if (nibble in 0..1) {
                    FieldReading.Known(FieldValue.Toggle(nibble == 1), byteArrayOf(raw[9]).hex())
                } else {
                    FieldReading.Unknown(byteArrayOf(raw[9]).hex())
                }
            }

            "0b01" -> fields[DeviceField.HIDE_SEARCH] = FieldReading.Unknown(byteArrayOf(raw[9]).hex())

            "0607", "0604" -> {
                val field = ztFields.getValue(command).single()
                val level = listOf(1, 3, 5).getOrNull(raw[9].u())
                fields[field] = level?.let { FieldReading.Known(FieldValue.Number(it), byteArrayOf(raw[9]).hex()) }
                    ?: FieldReading.Unknown(byteArrayOf(raw[9]).hex())
            }

            "0e03" -> {
                val level = if (profile.spec.ztPromptVolumeDirect) raw[9].u() else nibble + 1
                fields[DeviceField.PROMPT_VOLUME] = if (level in 1..5) {
                    FieldReading.Known(FieldValue.Number(level), byteArrayOf(raw[9]).hex())
                } else {
                    FieldReading.Unknown(byteArrayOf(raw[9]).hex())
                }
            }
        }
        return listOf(ProtocolReply(raw.hex(), setOf(command), fields))
    }

    override fun reset() = Unit
}

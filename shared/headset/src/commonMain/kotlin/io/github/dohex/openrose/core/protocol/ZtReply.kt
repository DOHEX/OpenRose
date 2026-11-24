package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.models.ModelProtocol

internal class ZtReply private constructor(
    val fields: Set<DeviceField>,
    private val minimumSize: Int,
    private val decode: ZtPayload.() -> Map<DeviceField, FieldReading>,
) {
    companion object {
        fun read(bytes: ByteArray, profile: ModelProfile, protocol: ModelProtocol.Zt): ProtocolReply {
            val raw = bytes.copyOf()
            val hex = raw.hex()
            if (raw.size < 7) return ProtocolReply(hex, warnings = listOf("通知短于命令字段"))
            val command = raw.copyOfRange(5, 7).hex()
            val layout = commands[command]
            return when {
                layout == null -> ProtocolReply(hex, warnings = listOf("未知字段 $command"))
                raw.size < layout.minimumSize -> ProtocolReply(hex, warnings = listOf("$command 字段被截断"))
                else -> ProtocolReply(hex, setOf(command), layout.decode(ZtPayload(raw, profile, protocol)))
            }
        }

        private fun single(
            field: DeviceField,
            minimumSize: Int = 10,
            read: ZtPayload.() -> FieldReading?,
        ): ZtReply = ZtReply(setOf(field), minimumSize) {
            val reading = read()
            if (reading == null) emptyMap() else mapOf(field to reading)
        }

        val commands = mapOf(
            "0101" to ZtReply(
                setOf(
                    DeviceField.LEFT_BATTERY,
                    DeviceField.RIGHT_BATTERY,
                    DeviceField.CASE_BATTERY,
                    DeviceField.LEFT_CHARGING,
                    DeviceField.RIGHT_CHARGING,
                ),
                16,
                ZtPayload::earbudBatteries,
            ),
            "0602" to single(DeviceField.NOISE_MODE, 14) {
                val tail = bytes.copyOfRange(9, bytes.size).hex()
                noiseModes[tail]?.let { FieldReading.Known(FieldValue.Text(it), tail) } ?: FieldReading.Unknown(tail)
            },
            "0201" to single(DeviceField.EQ) { choice(protocol.eqPresets) },
            "0603" to single(DeviceField.GAME_MODE) { toggle(on = 0) },
            "0801" to ZtReply(setOf(DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT), 10, ZtPayload::audioModes),
            "0d01" to single(DeviceField.FIRMWARE, 13) {
                val parts = (9..11).map { bytes[it].u() and 15 }
                FieldReading.Known(FieldValue.Firmware(parts, parts.joinToString("")), bytes.copyOfRange(9, 12).hex())
            },
            "0401" to single(DeviceField.TOUCH_KEYS, 17) {
                val bindings = (0..7).map {
                    val action = bytes[9 + it].u() and 15
                    TouchBinding(if (it < 4) "left" else "right", ztGestures[it % 4], ztActions.getOrNull(action), action)
                }
                FieldReading.Known(FieldValue.TouchKeys(bindings), bytes.copyOfRange(9, 17).hex())
            },
            "0a01" to single(DeviceField.TOUCH_ENABLED) { toggle(on = 0) },
            "0605" to single(DeviceField.NOISE_CYCLE, 13) {
                val flags = ztCycleOrder.mapIndexed { index, name ->
                    val flag = bytes[9 + index].u() and 15
                    name to if (flag in 0..1) flag == 1 else null
                }.toMap()
                FieldReading.Known(FieldValue.NoiseCycle(flags), bytes.copyOfRange(9, 13).hex())
            },
            "0c01" to single(DeviceField.EAR_DETECTION) { toggle(on = 0) },
            "0607" to single(DeviceField.ANC_LEVEL) { scalar(ztNoiseLevels.getOrNull(first)?.let(FieldValue::Number)) },
            "0604" to single(DeviceField.TRANSPARENCY_LEVEL) { scalar(ztNoiseLevels.getOrNull(first)?.let(FieldValue::Number)) },
            "0e03" to single(DeviceField.PROMPT_VOLUME) {
                val level = if (protocol.promptVolumeDirect) first else nibble + 1
                scalar(level.takeIf { it in 1..5 }?.let(FieldValue::Number))
            },
            "0103" to single(DeviceField.CASE_BATTERY) {
                if (profile.batteryLayout == BatteryLayout.SINGLE_UNIT) battery(9) else null
            },
            "0202" to single(DeviceField.CUSTOM_EQ, 19) {
                val gains = (9..18).map { offset ->
                    val encoded = bytes[offset].u()
                    if (encoded and 0x80 != 0) -(encoded and 0x7f) else encoded
                }
                FieldReading.Known(FieldValue.IntegerList(gains), bytes.copyOfRange(9, 19).hex())
            },
            "0203" to single(DeviceField.GAIN) { scalar((first - 1).takeIf { it in 0..2 }?.let(FieldValue::Number)) },
            "0802" to single(DeviceField.MULTIPOINT) { toggle(on = 1) },
            "0803" to ZtReply(emptySet(), 10) { emptyMap() },
            "0b01" to single(DeviceField.HIDE_SEARCH) { scalar(null) },
        )

        private val noiseModes = mapOf(
            "0100000020" to "anc",
            "0001000020" to "normal",
            "0000010020" to "wind",
            "0000000120" to "transparency",
            "0200000021" to "auto",
        )
    }
}

private class ZtPayload(val bytes: ByteArray, val profile: ModelProfile, val protocol: ModelProtocol.Zt) {
    val first = bytes[9].u()
    val nibble = first and 15

    fun scalar(value: FieldValue?): FieldReading {
        val raw = byteArrayOf(bytes[9]).hex()
        return if (value == null) FieldReading.Unknown(raw) else FieldReading.Known(value, raw)
    }

    fun toggle(on: Int): FieldReading = scalar(if (nibble in 0..1) FieldValue.Toggle(nibble == on) else null)

    fun choice(options: List<String>): FieldReading = scalar(options.getOrNull(nibble)?.let(FieldValue::Text))

    fun audioModes(): Map<DeviceField, FieldReading> = buildMap {
        put(DeviceField.AUDIO_MODE, choice(protocol.audioModes))
        if (!protocol.independentMultipoint) put(DeviceField.MULTIPOINT, toggle(on = 0))
    }

    fun battery(offset: Int, available: Boolean = true): FieldReading {
        val value = bytes[offset].u()
        val raw = byteArrayOf(bytes[offset]).hex()
        return when {
            value == 255 -> FieldReading.NotProvided(raw)
            available && value in 1..100 -> FieldReading.Known(FieldValue.Number(value), raw)
            else -> FieldReading.Unknown(raw)
        }
    }

    fun earbudBatteries(): Map<DeviceField, FieldReading> {
        if (profile.batteryLayout == BatteryLayout.SINGLE_UNIT) return emptyMap()
        val leftCharging = bytes[13].u() != 0
        val rightCharging = bytes[14].u() != 0
        return mapOf(
            DeviceField.LEFT_BATTERY to battery(11),
            DeviceField.RIGHT_BATTERY to battery(12),
            DeviceField.CASE_BATTERY to battery(15, available = leftCharging || rightCharging),
            DeviceField.LEFT_CHARGING to FieldReading.Known(FieldValue.Toggle(leftCharging), byteArrayOf(bytes[13]).hex()),
            DeviceField.RIGHT_CHARGING to FieldReading.Known(FieldValue.Toggle(rightCharging), byteArrayOf(bytes[14]).hex()),
        )
    }
}

package io.github.dohex.openrose.core.protocol

/** Fields requested by this tag and the decoder for its complete LTV value. */
private class Record(val fields: Set<DeviceField>, val read: RsV2Readings.() -> Unit)

private fun singleField(field: DeviceField, read: RsV2Readings.(DeviceField) -> Unit): Record = Record(setOf(field)) { read(field) }

private fun singleByte(field: DeviceField, decode: (Int) -> FieldValue?): Record = singleField(field) { target ->
    if (value.size == 1) put(target, decode(value[0].u()))
}

private fun toggle(field: DeviceField, on: Int): Record = singleByte(field) { raw ->
    if (raw in 0..1) FieldValue.Toggle(raw == on) else null
}

/** These records interpret the whole hex string as decimal digits, even when the value has more than one byte. */
private fun decimal(field: DeviceField, decode: (Int) -> FieldValue?): Record = singleField(field) { target ->
    if (value.isNotEmpty()) put(target, value.hex().toIntOrNull()?.let(decode))
}

private val touchSlots = listOf(1, 2, 3, 4, 0x11, 0x12, 0x13, 0x14)
private val tapSlots = listOf(2, 3, 0x12, 0x13)

private val records = mapOf(
    "01" to singleField(DeviceField.TOUCH_KEYS) { bindings(it, touchSlots) },
    "07" to toggle(DeviceField.TOUCH_ENABLED, on = 0),
    "08" to toggle(DeviceField.EAR_DETECTION, on = 0),
    "09" to singleByte(DeviceField.NOISE_MODE) { rsV2NoiseModes.getOrNull(it - 1)?.let(FieldValue::Text) },
    "0c" to Record(
        setOf(
            DeviceField.LEFT_BATTERY,
            DeviceField.RIGHT_BATTERY,
            DeviceField.CASE_BATTERY,
            DeviceField.LEFT_IN_CASE,
            DeviceField.RIGHT_IN_CASE,
            DeviceField.CASE_FLAG,
        ),
        RsV2Readings::battery,
    ),
    "0d" to singleField(DeviceField.FIRMWARE) { field ->
        if (value.size >= 3) {
            val parts = value.take(3).map { it.u() }
            put(field, FieldValue.Firmware(parts, parts.joinToString("")))
        }
    },
    "0e" to toggle(DeviceField.GAME_MODE, on = 1),
    "2a" to singleField(DeviceField.EQ, RsV2Readings::equalizer),
    "2b" to decimal(DeviceField.AUDIO_MODE) { rsV2AudioModes.getOrNull(it)?.let(FieldValue::Text) },
    "2c" to decimal(DeviceField.ANC_LEVEL, ::noiseLevel),
    "2d" to decimal(DeviceField.TRANSPARENCY_LEVEL, ::noiseLevel),
    "2e" to decimal(DeviceField.PROMPT_VOLUME) { it.takeIf { level -> level in 0..5 }?.let(FieldValue::Number) },
    "2f" to singleByte(DeviceField.FIND_STATE) { findStates[it]?.let(FieldValue::Text) },
    "31" to singleByte(DeviceField.PROMPT_LANGUAGE) { rsV2PromptLanguages.getOrNull(it)?.let(FieldValue::Text) },
    "32" to toggle(DeviceField.MULTIPOINT, on = 1),
    "36" to singleField(DeviceField.NOISE_CYCLE, RsV2Readings::noiseCycle),
    "38" to singleByte(DeviceField.SPATIAL_MODE) { rsV2SpatialModes.getOrNull(it)?.let(FieldValue::Text) },
    "3a" to toggle(DeviceField.EAR_ADAPTIVE, on = 1),
    "3d" to singleByte(DeviceField.SPATIAL_CONTENT) { rsV2SpatialContents.getOrNull(it)?.let(FieldValue::Text) },
    "49" to Record(setOf(DeviceField.INDEPENDENT_MIC_ACTIVATION, DeviceField.INDEPENDENT_MIC_MODE), RsV2Readings::microphone),
    "3b" to toggle(DeviceField.TAP_ENABLED, on = 1),
    "3c" to singleField(DeviceField.TAP_KEYS) { bindings(it, tapSlots, profile.capabilities().tapControl?.actions.orEmpty()) },
    "3e" to singleField(DeviceField.CUSTOM_EQ) { put(it, readCurve(value)) },
    "45" to singleByte(DeviceField.GAIN) { it.takeIf { level -> level in 0..2 }?.let(FieldValue::Number) },
    "46" to singleByte(DeviceField.HEARING_PROTECTION) { it.takeIf { level -> level in 0..6 }?.let(FieldValue::Number) },
)

internal val rsCommonV2Fields = records.mapValues { (_, record) -> record.fields }

internal fun readRsV2Record(key: String, profile: ModelProfile, value: ByteArray): Map<DeviceField, FieldReading> {
    val record = records[key] ?: return emptyMap()
    val payload = RsV2Readings(profile, value)
    record.read(payload)
    return payload.readings
}

private fun noiseLevel(number: Int): FieldValue? = number.takeIf { it == 1 || it == 3 || it == 5 }?.let(FieldValue::Number)

/** Decodes a single RS Common V2 record into the fields reported together by the device. */
private class RsV2Readings(val profile: ModelProfile, val value: ByteArray) {
    val readings = linkedMapOf<DeviceField, FieldReading>()
    fun put(field: DeviceField, semantic: FieldValue?, source: ByteArray = value) {
        readings[field] = semantic?.let { FieldReading.Known(it, source.hex()) } ?: FieldReading.Unknown(source.hex())
    }

    fun battery() {
        if (value.size < 3) return
        val left = value[0].u()
        val right = value[1].u()
        val case = value[2].u()
        val reportsCase = profile.batteryLayout == BatteryLayout.SINGLE_UNIT || inCase(left) || inCase(right)
        val caseAvailable = (case and 127) in 1..100 && reportsCase
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
            put(
                field,
                if (index < 2 && value[index].u() == 255) null else FieldValue.Toggle(value[index].u() and 128 != 0),
                byteArrayOf(value[index]),
            )
        }
    }

    fun bindings(field: DeviceField, slots: List<Int>, allowedActions: Set<String>? = null) {
        if (value.size < slots.size * 2 || value.size % 2 != 0) return
        val pairs = value.toList().chunked(2).associate { it[0].u() to it[1].u() }
        if (!slots.all { it in pairs }) return
        val bindings = slots.map { slot ->
            val action = pairs.getValue(slot)
            val name = rsV2Actions.getOrNull(action)?.takeIf { allowedActions == null || it in allowedActions }
            TouchBinding(if (slot < 0x10) "left" else "right", rsV2Gestures[(slot and 0x0f) - 1], name, action)
        }
        put(field, FieldValue.TouchKeys(bindings))
    }

    fun equalizer(field: DeviceField) {
        val raw = value.firstOrNull()?.u()
        if (value.size !in 1..2) return
        val index = if (value.size == 1) {
            when (raw) {
                0 -> 1
                1 -> 0
                else -> raw
            }
        } else {
            raw
        }
        val presetName = mapOf(
            0 to "pop",
            1 to "hifi",
            2 to "rock",
            3 to "light",
            4 to "custom",
            5 to "custom_slot",
            6 to "eap",
            0xa0 to "game_1",
        )[index]
        put(field, presetName?.let { FieldValue.Text(it) })
        if (value.size == 2 && raw == 5) put(DeviceField.EQ_SLOT, FieldValue.Number(value[1].u()))
    }

    fun noiseCycle(field: DeviceField) {
        if (value.size < 4) return
        val flags = rsV2CycleOrder.mapIndexed { index, name ->
            val flag = value[index].u()
            name to if (flag in 0..1) flag == 1 else null
        }.toMap()
        val supported = profile.capabilities().noiseControl?.cycleModes.orEmpty()
        put(field, FieldValue.NoiseCycle(flags.filterKeys { it in supported }))
    }

    fun microphone() {
        // Longer 49 records carry extended firmware versions, not microphone states.
        if (value.size != 2 || value.any { it.u() !in 0..1 }) return
        val activated = value[0].u() == 1
        put(DeviceField.INDEPENDENT_MIC_ACTIVATION, FieldValue.Toggle(activated))
        put(DeviceField.INDEPENDENT_MIC_MODE, FieldValue.Toggle(activated && value[1].u() == 1))
    }
}

internal val rsV2NoiseModes = listOf("anc", "normal", "transparency", "wind", "auto", "deep", "dynamic")
private val findStates = mapOf(
    1 to "start_left",
    2 to "start_right",
    3 to "start_both",
    4 to "stop_both",
    5 to "stop_left",
    6 to "stop_right",
)

private fun inCase(value: Int): Boolean = value and 128 != 0 && value != 255

private fun readCurve(bytes: ByteArray): FieldValue? {
    val gains = bytes.map { if (it.u() and 128 != 0) -(it.u() and 127) else it.u() and 127 }
    return gains.takeIf { it.size == 10 && it.all { gain -> gain in -6..6 } }?.let(FieldValue::IntegerList)
}

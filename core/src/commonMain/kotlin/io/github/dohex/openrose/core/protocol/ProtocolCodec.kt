package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.TouchSlot
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

enum class DeviceField(val displayName: String) {
    LEFT_BATTERY("左耳电量"),
    RIGHT_BATTERY("右耳电量"),
    CASE_BATTERY("盒电量"),
    LEFT_CHARGING("左耳充电"),
    RIGHT_CHARGING("右耳充电"),
    LEFT_IN_CASE("左耳盒内标志"),
    RIGHT_IN_CASE("右耳盒内标志"),
    CASE_FLAG("盒原始标志"),
    FIRMWARE("固件"),
    NOISE_MODE("降噪模式"),
    EQ("均衡器"),
    EQ_SLOT("自定义 EQ 槽位"),
    GAME_MODE("游戏模式"),
    AUDIO_MODE("音频模式"),
    MULTIPOINT("多点连接"),
    TOUCH_KEYS("触摸绑定"),
    TOUCH_ENABLED("触摸开关"),
    NOISE_CYCLE("降噪轮换"),
    EAR_DETECTION("佩戴检测功能"),
    ANC_LEVEL("降噪档位"),
    TRANSPARENCY_LEVEL("通透档位"),
    PROMPT_VOLUME("提示音档位"),
    CUSTOM_EQ("自定义 EQ"),
    GAIN("增益档位"),
    HEARING_PROTECTION("听力保护档位"),
    PROMPT_LANGUAGE("提示音语言"),
    FIND_STATE("寻机事件"),
}

sealed interface FieldReading {
    val rawHex: String
    data object Loading : FieldReading {
        override val rawHex = ""
    }
    data class NotProvided(override val rawHex: String) : FieldReading
    data class Unknown(override val rawHex: String) : FieldReading
    data class Known(val value: FieldValue, override val rawHex: String) : FieldReading
}

data class TouchBinding(val side: String, val gesture: String, val action: String?, val rawAction: Int)

sealed interface FieldValue {
    val displayText: String
    data class Number(val value: Int) : FieldValue {
        override val displayText get() = value.toString()
    }
    data class Toggle(val value: Boolean) : FieldValue {
        override val displayText get() = if (value) "开启" else "关闭"
    }
    data class Text(val value: String) : FieldValue {
        override val displayText get() = value
    }
    data class IntegerList(val values: PersistentList<Int>) : FieldValue {
        constructor(values: List<Int>) : this(values.toPersistentList())
        override val displayText get() = values.joinToString(", ")
    }
    data class Firmware(val parts: PersistentList<Int>, override val displayText: String) : FieldValue {
        constructor(parts: List<Int>, displayText: String) : this(parts.toPersistentList(), displayText)
    }
    data class TouchKeys(val bindings: PersistentList<TouchBinding>) : FieldValue {
        constructor(bindings: List<TouchBinding>) : this(bindings.toPersistentList())
        override val displayText get() = bindings.joinToString("；") { "${it.side}/${it.gesture}: ${it.action ?: "未知(${it.rawAction})"}" }
    }

    /** One requested slot assignment; the device still reports its complete TouchKeys value. */
    data class TouchAssignment(val side: String, val gesture: String, val action: String) : FieldValue {
        override val displayText get() = "$side/$gesture: $action"
    }
    data class NoiseCycle(val flags: PersistentMap<String, Boolean?>) : FieldValue {
        constructor(flags: Map<String, Boolean?>) : this(flags.toPersistentMap())
        override val displayText get() = flags.entries.joinToString("，") {
            "${it.key}: ${when (it.value) {
                true -> "开"
                false -> "关"
                null -> "未知"
            }}"
        }
    }
}

data class ProtocolQuery(
    val key: String,
    val bytes: ByteArray,
    val responseKeys: Set<String>,
    val expectedFields: Set<DeviceField>,
)

/** A write plan and the independent state read used to confirm its result. */
data class ProtocolSetting(
    val writeFrames: List<ByteArray>,
    val readback: ProtocolQuery,
    val expectedValues: Map<DeviceField, FieldValue>,
    val additionalReadbacks: List<ProtocolQuery> = emptyList(),
) {
    val readbacks: List<ProtocolQuery> get() = listOf(readback) + additionalReadbacks
}

data class ProtocolReply(
    val rawHex: String,
    val responseKeys: Set<String> = emptySet(),
    val readings: Map<DeviceField, FieldReading> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val accepted: Boolean = readings.any { (field, reading) ->
        field !in batteryFlags && reading is FieldReading.Known && when (val value = reading.value) {
            is FieldValue.TouchKeys -> value.bindings.any { it.action != null }
            is FieldValue.NoiseCycle -> value.flags.values.any { it != null }
            else -> true
        }
    },
)

private val batteryFlags = setOf(
    DeviceField.LEFT_CHARGING,
    DeviceField.RIGHT_CHARGING,
    DeviceField.LEFT_IN_CASE,
    DeviceField.RIGHT_IN_CASE,
    DeviceField.CASE_FLAG,
)

interface ProtocolCodec {
    val profile: ModelProfile
    fun initQueries(maxWriteSize: Int): List<ProtocolQuery>
    fun prepareSetting(
        setting: DeviceSetting,
        value: FieldValue,
        firmware: FieldValue.Firmware? = null,
    ): ProtocolSetting = throw UnsupportedOperationException("该型号尚未实现此设置")
    fun feedNotification(bytes: ByteArray): List<ProtocolReply>
    fun reset()
}

fun createProtocolCodec(profile: ModelProfile): ProtocolCodec = when (profile.family) {
    ProtocolFamily.ZT -> ZtCodec(profile)
    ProtocolFamily.RS_COMMON_V2 -> RsCommonV2Codec(profile)
}

internal fun ByteArray.hex(): String = joinToString("") { it.u().toString(16).padStart(2, '0') }
internal fun Byte.u(): Int = toInt() and 255
internal fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun validatedNoiseCycle(value: FieldValue): FieldValue.NoiseCycle {
    require(value is FieldValue.NoiseCycle) { "轮换需要四种模式的明确开关" }
    val flags = value.flags.toMap()
    require(flags.keys == setOf("normal", "transparency", "wind", "anc")) { "轮换必须包含普通、通透、抗风和 ANC 四项" }
    require(flags.values.all { it != null }) { "轮换不能包含未知开关" }
    return FieldValue.NoiseCycle(flags)
}

internal fun validatedTouchAssignment(value: FieldValue, profile: ModelProfile): FieldValue.TouchAssignment {
    require(value is FieldValue.TouchAssignment) { "触控绑定需要单个耳侧、手势和动作" }
    val capabilities = profile.capabilities().touchControl ?: throw UnsupportedOperationException("该型号不支持触控绑定")
    require(TouchSlot(value.side, value.gesture) in capabilities.slots) { "该触控槽位无效" }
    if (value.action !in capabilities.actions) throw UnsupportedOperationException("该型号不支持此触控动作")
    return value
}

internal val ztFields = linkedMapOf(
    "0101" to setOf(
        DeviceField.LEFT_BATTERY,
        DeviceField.RIGHT_BATTERY,
        DeviceField.CASE_BATTERY,
        DeviceField.LEFT_CHARGING,
        DeviceField.RIGHT_CHARGING,
    ),
    "0602" to setOf(DeviceField.NOISE_MODE), "0201" to setOf(DeviceField.EQ),
    "0603" to setOf(DeviceField.GAME_MODE), "0801" to setOf(DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT),
    "0d01" to setOf(DeviceField.FIRMWARE), "0401" to setOf(DeviceField.TOUCH_KEYS),
    "0a01" to setOf(DeviceField.TOUCH_ENABLED), "0605" to setOf(DeviceField.NOISE_CYCLE),
    "0c01" to setOf(DeviceField.EAR_DETECTION), "0607" to setOf(DeviceField.ANC_LEVEL),
    "0604" to setOf(DeviceField.TRANSPARENCY_LEVEL), "0e03" to setOf(DeviceField.PROMPT_VOLUME),
)

internal val rsCommonV2Fields = linkedMapOf(
    "01" to setOf(DeviceField.TOUCH_KEYS), "07" to setOf(DeviceField.TOUCH_ENABLED),
    "08" to setOf(DeviceField.EAR_DETECTION), "09" to setOf(DeviceField.NOISE_MODE),
    "0c" to setOf(
        DeviceField.LEFT_BATTERY,
        DeviceField.RIGHT_BATTERY,
        DeviceField.CASE_BATTERY,
        DeviceField.LEFT_IN_CASE,
        DeviceField.RIGHT_IN_CASE,
        DeviceField.CASE_FLAG,
    ),
    "0d" to setOf(DeviceField.FIRMWARE), "0e" to setOf(DeviceField.GAME_MODE),
    "2a" to setOf(DeviceField.EQ), "2b" to setOf(DeviceField.AUDIO_MODE),
    "2c" to setOf(DeviceField.ANC_LEVEL), "2d" to setOf(DeviceField.TRANSPARENCY_LEVEL),
    "2e" to setOf(DeviceField.PROMPT_VOLUME), "2f" to setOf(DeviceField.FIND_STATE),
    "31" to setOf(DeviceField.PROMPT_LANGUAGE), "32" to setOf(DeviceField.MULTIPOINT), "36" to setOf(DeviceField.NOISE_CYCLE),
    "3e" to setOf(DeviceField.CUSTOM_EQ), "45" to setOf(DeviceField.GAIN), "46" to setOf(DeviceField.HEARING_PROTECTION),
)

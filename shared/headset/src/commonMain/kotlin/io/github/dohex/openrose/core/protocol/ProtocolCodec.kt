package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.models.ModelProtocol
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
    TAP_ENABLED("轻拍开关"),
    TAP_KEYS("轻拍绑定"),
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
    SPATIAL_AUDIO("空间音频"),
    HIDE_SEARCH("隐藏搜索"),
    SPATIAL_MODE("空间模式"),
    SPATIAL_CONTENT("空间音频内容"),
    EAR_ADAPTIVE("耳道自适应"),
    INDEPENDENT_MIC_ACTIVATION("独立麦克风激活"),
    INDEPENDENT_MIC_MODE("独立麦克风模式"),
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
    /** Minimum pause before this initialization query, when required by the model. */
    val delayBeforeMillis: Long = 0,
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

    /** Reads that become applicable once the initial batch has returned the firmware. */
    fun firmwareInitQueries(firmware: FieldValue.Firmware?): List<ProtocolQuery> = emptyList()

    /** Both real and simulated writes use the facts the current device has actually reported. */
    fun prepareSetting(command: HeadsetCommand, device: DeviceState): ProtocolSetting = prepareSetting(
        command.setting,
        command.value,
        device.knownValue(DeviceField.FIRMWARE) as? FieldValue.Firmware,
        device.diagnostics.fields,
    )

    fun prepareSetting(
        setting: DeviceSetting,
        value: FieldValue,
        firmware: FieldValue.Firmware? = null,
        currentFields: Map<DeviceField, FieldReading> = emptyMap(),
    ): ProtocolSetting = throw UnsupportedOperationException("该型号尚未实现此设置")
    fun feedNotification(bytes: ByteArray): List<ProtocolReply>
    fun reset()
}

fun createProtocolCodec(profile: ModelProfile): ProtocolCodec = when (val protocol = profile.spec.protocol) {
    is ModelProtocol.Zt -> ZtCodec(profile, protocol)
    is ModelProtocol.RsCommonV2 -> RsCommonV2Codec(profile, protocol)
    is ModelProtocol.F8 -> F8Codec(profile, protocol)
    is ModelProtocol.RsCommon -> RsCommonCodec(profile, protocol)
}

internal fun ByteArray.hex(): String = joinToString("") { it.u().toString(16).padStart(2, '0') }
internal fun Byte.u(): Int = toInt() and 255
internal fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun validatedNoiseCycle(
    value: FieldValue,
    modes: Set<String> = setOf("normal", "transparency", "wind", "anc"),
): FieldValue.NoiseCycle {
    require(value is FieldValue.NoiseCycle) { "轮换需要四种模式的明确开关" }
    val flags = value.flags.toMap()
    require(modes.isNotEmpty() && flags.keys == modes) { "轮换必须包含该型号的全部可选模式" }
    require(flags.values.all { it != null }) { "轮换不能包含未知开关" }
    return FieldValue.NoiseCycle(flags)
}

/** ZT and RS Common V2 both encode each signed band gain in one sign-magnitude byte. */
internal fun customCurveBytes(value: FieldValue, capability: CustomEqualizerCapabilities?): ByteArray {
    require(value is FieldValue.IntegerList) { "自定义均衡器需要整数曲线" }
    if (capability == null) throw UnsupportedOperationException("该型号不支持自定义均衡器")
    val gains = value.values
    require(gains.size == capability.bandsHz.size && gains.all { it in capability.minGain..capability.maxGain }) {
        "自定义均衡器需要 ${capability.bandsHz.size} 段 ${capability.minGain}..${capability.maxGain} 的整数"
    }
    return ByteArray(gains.size) { i ->
        val gain = gains[i]
        (if (gain < 0) 0x80 or -gain else gain).toByte()
    }
}

internal fun validatedTouchAssignment(
    value: FieldValue,
    capabilities: TouchControlCapabilities?,
): FieldValue.TouchAssignment {
    require(value is FieldValue.TouchAssignment) { "触控绑定需要单个耳侧、手势和动作" }
    if (capabilities == null) throw UnsupportedOperationException("该型号不支持此按键绑定")
    require(TouchSlot(value.side, value.gesture) in capabilities.slots) { "该触控槽位无效" }
    if (value.action !in capabilities.actions) throw UnsupportedOperationException("该型号不支持此触控动作")
    return value
}

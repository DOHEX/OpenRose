package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

/** Presentation labels only; protocol numeric values and capability decisions stay in the core. */
internal fun displayFieldValue(
    field: DeviceField?,
    value: FieldValue,
    profile: ModelProfile? = null,
    touch: TouchControlCapabilities? = null,
): String = when (value) {
    is FieldValue.Number -> numberLabel(field, value)

    is FieldValue.Toggle -> toggleLabel(field, value)

    is FieldValue.Text -> if (field == DeviceField.EQ) equalizerLabel(value.value, profile) else semanticLabel(value.value)

    is FieldValue.TouchKeys -> value.bindings.joinToString("\n") { binding ->
        val action = binding.action?.let(::semanticLabel) ?: "未知操作（${binding.rawAction}）"
        "${touchSlotLabel(binding.side, binding.gesture, touch)}：$action"
    }

    is FieldValue.TouchAssignment ->
        "${touchSlotLabel(value.side, value.gesture, touch)}绑定为${semanticLabel(value.action)}"

    is FieldValue.NoiseCycle -> value.flags.entries.joinToString("\n") { (mode, enabled) ->
        "${semanticLabel(mode)}：${when (enabled) {
            true -> "开启"
            false -> "关闭"
            null -> "未知"
        }}"
    }

    is FieldValue.Firmware, is FieldValue.IntegerList -> value.displayText
}

private val batteryFields = setOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)

/** Battery readings with their labels, in display order; a single unit reports in the case slot. */
internal fun ModelProfile.batteryReadings(): List<Pair<String, DeviceField>> = when (batteryLayout) {
    BatteryLayout.EARBUDS_AND_CASE -> listOf(
        "左耳" to DeviceField.LEFT_BATTERY,
        "右耳" to DeviceField.RIGHT_BATTERY,
        "充电盒" to DeviceField.CASE_BATTERY,
    )

    BatteryLayout.EARBUDS_ONLY -> listOf("左耳" to DeviceField.LEFT_BATTERY, "右耳" to DeviceField.RIGHT_BATTERY)

    BatteryLayout.SINGLE_UNIT -> listOf("电量" to DeviceField.CASE_BATTERY)
}

internal fun gainLevelLabel(level: Int): String = when (level) {
    0 -> "低"
    1 -> "中"
    2 -> "高"
    else -> "未知（$level）"
}

internal fun hearingProtectionLabel(level: Int): String = when (level) {
    0 -> "关闭"
    in 1..6 -> "${70 + level * 5} dB"
    else -> "未知（$level）"
}

internal fun touchSlotLabel(side: String, gesture: String, touch: TouchControlCapabilities?): String {
    val label = touch?.slotLabels?.get(TouchSlot(side, gesture)) ?: touchSideLabel(side)
    return label + touchGestureLabel(gesture)
}

internal fun touchSideLabel(side: String): String = when (side) {
    "left" -> "左耳"
    "right" -> "右耳"
    else -> side
}

internal fun touchGestureLabel(gesture: String): String = when (gesture) {
    "single" -> "单击"
    "double" -> "双击"
    "triple" -> "三击"
    "long" -> "长按"
    else -> gesture
}

internal fun equalizerLabel(preset: String, profile: ModelProfile?): String {
    val code = when (preset) {
        "pop" -> "POP"
        "hifi" -> "HIFI"
        "rock" -> "ROCK"
        "light" -> "LIGHT"
        "eap" -> "EAP"
        else -> return profile?.equalizerPresetLabel(preset) ?: semanticLabel(preset)
    }
    val label = profile?.equalizerPresetLabel(preset)
    return if (label == null || label.equals(code, ignoreCase = true)) code else "$label · $code"
}

private val semanticLabels = mapOf(
    "anc" to "主动降噪",
    "normal" to "标准模式",
    "wind" to "抗风噪",
    "transparency" to "通透",
    "auto" to "自动",
    "deep" to "深度降噪",
    "dynamic" to "动态降噪",
    "aac_sbc" to "AAC / SBC",
    "ldac" to "LDAC",
    "ldac_lhdc" to "LDAC + LHDC",
    "lhdc" to "LHDC",
    "lc3" to "LC3",
    "chinese" to "中文",
    "english" to "英文",
    "pop" to "流行",
    "hifi" to "Hi-Fi",
    "rock" to "摇滚",
    "light" to "轻音乐",
    "custom" to "自定义",
    "off" to "关闭",
    "head_tracking" to "头部跟踪",
    "fixed" to "固定",
    "tv" to "电视",
    "music" to "音乐",
    "movie" to "电影",
    "game" to "游戏",
    "aptx" to "aptX",
    "aptx_hd" to "aptX HD",
    "aptx_adaptive" to "aptX Adaptive",
    "game_1" to "游戏 1",
    "custom_slot" to "自定义槽位",
    "eap" to "EAP",
    "volume_up" to "增加音量",
    "volume_down" to "减小音量",
    "previous_track" to "上一曲",
    "next_track" to "下一曲",
    "voice_assistant" to "语音助手",
    "play_pause" to "播放 / 暂停",
    "none" to "无操作",
    "noise_cycle" to "降噪轮换",
    "game_mode" to "游戏模式",
    "start_left" to "开始左耳寻机",
    "start_right" to "开始右耳寻机",
    "start_both" to "开始双耳寻机",
    "stop_both" to "停止双耳寻机",
    "stop_left" to "停止左耳寻机",
    "stop_right" to "停止右耳寻机",
)

internal fun semanticLabel(value: String): String = semanticLabels[value] ?: value

/** The retail name with its hardware qualifier when more than one configuration shares it. */
val ModelProfile.configurationLabel: String
    get() = branchLabel?.let { "$displayName · $it" } ?: displayName

/** The caption below a device's Bluetooth name. */
internal val ModelProfile.configurationCaption: String
    get() = listOfNotNull(branchLabel, family.displayName).joinToString(" · ")

private val ModelProfile.branchLabel: String?
    get() = when (this) {
        ModelProfile.CERAMICS_X_F8 -> "标准版"
        ModelProfile.CERAMICS_X_7034_F8 -> "7034"
        ModelProfile.OPENFEEL_LDAC_RS_COMMON -> "LDAC"
        ModelProfile.OPENFEEL_LHDC_RS_COMMON -> "LHDC"
        ModelProfile.OPENFEEL_DUAL_RS_COMMON -> "LDAC + LHDC"
        ModelProfile.OPENFUN_RS_COMMON -> "无触控开关"
        ModelProfile.OPENFUN_TOUCH_RS_COMMON -> "有触控开关"
        else -> null
    }

private fun numberLabel(field: DeviceField?, value: FieldValue.Number): String = when {
    field in batteryFields -> "${value.value}%"
    field == DeviceField.PROMPT_VOLUME && value.value == 0 -> "关闭"
    field == DeviceField.GAIN -> gainLevelLabel(value.value)
    field == DeviceField.HEARING_PROTECTION -> hearingProtectionLabel(value.value)
    else -> value.displayText
}

private fun toggleLabel(field: DeviceField?, value: FieldValue.Toggle): String = when (field) {
    DeviceField.LEFT_CHARGING, DeviceField.RIGHT_CHARGING -> if (value.value) "充电中" else "未充电"
    DeviceField.LEFT_IN_CASE, DeviceField.RIGHT_IN_CASE -> if (value.value) "在盒内" else "不在盒内"
    DeviceField.CASE_FLAG -> if (value.value) "已置位" else "未置位"
    else -> value.displayText
}

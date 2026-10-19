package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

/** Presentation labels only; protocol numeric values and capability decisions stay in the core. */
internal fun displayFieldValue(field: DeviceField, value: FieldValue, profile: ModelProfile? = null): String = when (value) {
    is FieldValue.Number -> if (field in batteryFields) "${value.value}%" else value.displayText
    is FieldValue.Toggle -> when (field) {
        DeviceField.LEFT_CHARGING, DeviceField.RIGHT_CHARGING -> if (value.value) "充电中" else "未充电"
        DeviceField.LEFT_IN_CASE, DeviceField.RIGHT_IN_CASE -> if (value.value) "在盒内" else "不在盒内"
        DeviceField.CASE_FLAG -> if (value.value) "已置位" else "未置位"
        else -> value.displayText
    }
    is FieldValue.Text -> if (field == DeviceField.EQ) equalizerLabel(value.value, profile) else semanticLabel(value.value)
    is FieldValue.TouchKeys -> value.bindings.joinToString("\n") { binding ->
        "${touchSideLabel(binding.side)}${touchGestureLabel(binding.gesture)}：${binding.action?.let(::semanticLabel) ?: "未知操作（${binding.rawAction}）"}"
    }
    is FieldValue.TouchAssignment ->
        "${touchSideLabel(value.side)}${touchGestureLabel(value.gesture)}绑定为${semanticLabel(value.action)}"
    is FieldValue.NoiseCycle -> value.flags.entries.joinToString("\n") { (mode, enabled) ->
        "${semanticLabel(mode)}：${when (enabled) { true -> "开启"; false -> "关闭"; null -> "未知" }}"
    }
    is FieldValue.Firmware, is FieldValue.IntegerList -> value.displayText
}

private val batteryFields = setOf(DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY)

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
        else -> return semanticLabel(preset)
    }
    val label = when (profile) {
        ModelProfile.I5_ZT -> when (preset) {
            "pop" -> "弱水经典"
            "hifi" -> "日系柔情"
            "rock" -> "乐器大师"
            "light" -> "清新空灵"
            else -> null
        }
        ModelProfile.I7_RS_V2 -> when (preset) {
            "pop" -> "弱水经典"
            "hifi" -> "原声再现"
            "rock" -> "古典乐器"
            "light" -> "日系甜美"
            "eap" -> "欧美流行"
            else -> null
        }
        null -> null
    }
    return if (label == null) code else "$label · $code"
}

internal fun semanticLabel(value: String): String = when (value) {
    "anc" -> "主动降噪"
    "normal" -> "标准模式"
    "wind" -> "抗风噪"
    "transparency" -> "通透"
    "auto" -> "自动"
    "deep" -> "深度降噪"
    "dynamic" -> "动态降噪"
    "aac_sbc" -> "AAC / SBC"
    "ldac" -> "LDAC"
    "lhdc" -> "LHDC"
    "lc3" -> "LC3"
    "pop" -> "流行"
    "hifi" -> "Hi-Fi"
    "rock" -> "摇滚"
    "light" -> "轻音乐"
    "custom" -> "自定义"
    "custom_slot" -> "自定义槽位"
    "eap" -> "EAP"
    "volume_up" -> "增加音量"
    "volume_down" -> "减小音量"
    "previous_track" -> "上一曲"
    "next_track" -> "下一曲"
    "voice_assistant" -> "语音助手"
    "play_pause" -> "播放 / 暂停"
    "none" -> "无操作"
    "noise_cycle" -> "降噪轮换"
    "game_mode" -> "游戏模式"
    "start_left" -> "开始左耳寻机"
    "start_right" -> "开始右耳寻机"
    "start_both" -> "开始双耳寻机"
    "stop_both" -> "停止双耳寻机"
    "stop_left" -> "停止左耳寻机"
    "stop_right" -> "停止右耳寻机"
    else -> value
}

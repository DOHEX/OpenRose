package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

@Composable
internal fun DeviceControlCard(
    state: ControlState,
    device: DeviceState,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val writable = device.capabilities.writableSettings
    val audio = device.capabilities.audioControl
    val hasAudio = DeviceSetting.AUDIO_MODE in writable && audio != null && audio.modes.isNotEmpty()
    val hasMultipoint = DeviceSetting.MULTIPOINT in writable
    val volumeLevels = device.capabilities.promptVolumeLevels
    val hasVolume = DeviceSetting.PROMPT_VOLUME in writable && volumeLevels.isNotEmpty()
    val gainLevels = device.capabilities.gainLevels
    val hasGain = DeviceSetting.GAIN in writable && gainLevels.isNotEmpty()
    val protectionLevels = device.capabilities.hearingProtectionLevels
    val hasProtection = DeviceSetting.HEARING_PROTECTION in writable && protectionLevels.isNotEmpty()
    if (!hasAudio && !hasMultipoint && !hasVolume && !hasGain && !hasProtection) return

    val enabled = state.isReady(device) && !state.hasPendingSetting(device)

    if (hasAudio || hasMultipoint) {
        val footnote = buildList {
            if (hasAudio) add("这里只设置耳机音频偏好；实际编码以系统连接协商为准。")
            if (hasAudio && hasMultipoint) {
                add(
                    if (audio?.multipointCoupled == true) {
                        "该型号的 LDAC 与多点连接互斥；切换多点时设备会改用 AAC / SBC。"
                    } else {
                        "音频模式与多点连接分别设置，可用组合仍由耳机与连接设备决定。"
                    },
                )
            }
        }.joinToString(" ").takeIf { it.isNotEmpty() }
        AppGroup(title = "音频", footnote = footnote) {
            if (hasAudio) {
                val confirmed = (device.knownValue(DeviceField.AUDIO_MODE) as? FieldValue.Text)?.value
                AppChoiceRow(
                    title = "音频模式",
                    valueText = confirmedChoiceValue(DeviceField.AUDIO_MODE, device),
                    options = audio!!.modes.sortedBy(::semanticLabel)
                        .map { mode -> AppOption(mode, semanticLabel(mode)) },
                    selected = confirmed,
                    enabled = enabled,
                    onSelect = { mode ->
                        if (mode != confirmed) onSet(DeviceSetting.AUDIO_MODE, FieldValue.Text(mode))
                    },
                )
            }
            if (hasMultipoint) {
                SettingToggleChoices("多点连接", DeviceField.MULTIPOINT, DeviceSetting.MULTIPOINT, device, enabled, onSet)
            }
        }
    }

    if (hasVolume) {
        val confirmed = (device.knownValue(DeviceField.PROMPT_VOLUME) as? FieldValue.Number)?.value
        AppGroup(title = "提示与音量") {
            AppChoiceRow(
                title = "提示音音量",
                valueText = confirmedChoiceValue(DeviceField.PROMPT_VOLUME, device),
                options = volumeLevels.sorted().map { level -> AppOption(level, if (level == 0) "关闭" else "$level 档") },
                selected = confirmed,
                enabled = enabled,
                onSelect = { level ->
                    if (level != confirmed) onSet(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level))
                },
            )
        }
    }

    if (hasGain || hasProtection) {
        AppGroup(
            title = "输出与保护",
            footnote = if (hasProtection) "阈值显示型号提供的选项，不表示测得声压或实际保护效果。" else null,
        ) {
            if (hasGain) {
                val confirmed = (device.knownValue(DeviceField.GAIN) as? FieldValue.Number)?.value
                AppChoiceRow(
                    title = "增益档位",
                    valueText = confirmedChoiceValue(DeviceField.GAIN, device),
                    options = gainLevels.sorted().map { level -> AppOption(level, gainLevelLabel(level)) },
                    selected = confirmed,
                    enabled = enabled,
                    onSelect = { level ->
                        if (level != confirmed) onSet(DeviceSetting.GAIN, FieldValue.Number(level))
                    },
                )
            }
            if (hasProtection) {
                val confirmed = (device.knownValue(DeviceField.HEARING_PROTECTION) as? FieldValue.Number)?.value
                AppChoiceRow(
                    title = "听力保护阈值",
                    valueText = confirmedChoiceValue(DeviceField.HEARING_PROTECTION, device),
                    options = protectionLevels.sorted().map { level -> AppOption(level, hearingProtectionLabel(level)) },
                    selected = confirmed,
                    enabled = enabled,
                    onSelect = { level ->
                        if (level != confirmed) onSet(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(level))
                    },
                )
            }
        }
    }
}

private fun confirmedChoiceValue(field: DeviceField, device: DeviceState): String = when (val reading = device.fields[field]) {
    is FieldReading.Known -> displayFieldValue(field, reading.value, device.profile)
    is FieldReading.Unknown -> "当前状态未知"
    is FieldReading.NotProvided -> "设备未提供"
    FieldReading.Loading, null -> readingText(field, reading, false, device.fieldErrors[field], device.profile)
}

@Composable
internal fun SettingToggleChoices(
    label: String,
    field: DeviceField,
    setting: DeviceSetting,
    device: DeviceState,
    enabled: Boolean,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val selected = (device.knownValue(field) as? FieldValue.Toggle)?.value
    val summary = when (selected) {
        true -> "设备已确认：开启"
        false -> "设备已确认：关闭"
        null -> when (device.fields[field]) {
            is FieldReading.Known, is FieldReading.Unknown -> "当前状态未知"
            else -> readingText(field, device.fields[field], false, device.fieldErrors[field], device.profile)
        }
    }
    if (selected != null) {
        AppSwitchRow(
            title = label,
            summary = summary,
            checked = selected,
            enabled = enabled,
            onChange = { requested ->
                if (requested != selected) onSet(setting, FieldValue.Toggle(requested))
            },
        )
    } else {
        AppUnknownToggleRow(
            title = label,
            summary = summary,
            enabled = enabled,
            onRequest = { requested -> onSet(setting, FieldValue.Toggle(requested)) },
        )
    }
}

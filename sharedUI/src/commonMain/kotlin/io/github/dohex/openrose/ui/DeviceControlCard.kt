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
    val hasSpatialAudio = DeviceSetting.SPATIAL_AUDIO in writable
    val volumeLevels = device.capabilities.promptVolumeLevels
    val hasVolume = DeviceSetting.PROMPT_VOLUME in writable && volumeLevels.isNotEmpty()
    val gainLevels = device.capabilities.gainLevels
    val hasGain = DeviceSetting.GAIN in writable && gainLevels.isNotEmpty()
    val protectionLevels = device.capabilities.hearingProtectionLevels
    val hasProtection = DeviceSetting.HEARING_PROTECTION in writable && protectionLevels.isNotEmpty()
    val bassLevels = device.capabilities.bassBoostLevels
    val hasBass = DeviceSetting.BASS_BOOST in writable && bassLevels.isNotEmpty()
    val languages = device.capabilities.promptLanguages
    val hasLanguage = DeviceSetting.PROMPT_LANGUAGE in writable && languages.isNotEmpty()
    val hasPrompt = hasVolume || hasLanguage
    val hasAudioControls = hasAudio || hasMultipoint || hasSpatialAudio
    val hasSound = hasGain || hasProtection || hasBass
    if (!hasPrompt && !hasAudioControls && !hasSound) return

    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    if (hasPrompt) {
        AppGroup(title = "提示与音量") {
            if (hasVolume) {
                val confirmed = (device.knownValue(DeviceField.PROMPT_VOLUME) as? FieldValue.Number)?.value
                AppChoiceRow(
                    title = "提示音音量",
                    valueText = confirmedChoiceValue(DeviceField.PROMPT_VOLUME, device),
                    options = volumeLevels.sorted().map { level -> AppOption(level, if (level == 0) "关闭" else "$level 档") },
                    selected = confirmed,
                    enabled = enabled,
                    onSelect = { level -> if (level != confirmed) onSet(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level)) },
                )
            }
            if (hasLanguage) {
                val confirmed = (device.knownValue(DeviceField.PROMPT_LANGUAGE) as? FieldValue.Text)?.value
                AppChoiceRow(
                    title = "提示音语言",
                    valueText = confirmedChoiceValue(DeviceField.PROMPT_LANGUAGE, device),
                    options = languages.sorted().map { AppOption(it, semanticLabel(it)) },
                    selected = confirmed,
                    enabled = enabled,
                    onSelect = { if (it != confirmed) onSet(DeviceSetting.PROMPT_LANGUAGE, FieldValue.Text(it)) },
                )
            }
        }
    }

    if (hasBass) {
        val mode = device.fields[DeviceField.EQ] as? FieldReading.Known
        val slot = device.fields[DeviceField.EQ_SLOT] as? FieldReading.Known
        val confirmed = if (mode != null && (mode.value as? FieldValue.Text)?.value == "custom_slot" && mode.rawHex == slot?.rawHex) {
            (slot.value as? FieldValue.Number)?.value
        } else {
            null
        }
        AppGroup(title = "音效") {
            AppChoiceRow(
                title = "低音增强",
                valueText = confirmed?.let { "$it 档" } ?: "当前状态未知",
                options = bassLevels.sorted().map { AppOption(it, "$it 档") },
                selected = confirmed,
                enabled = enabled,
                onSelect = { if (it != confirmed) onSet(DeviceSetting.BASS_BOOST, FieldValue.Number(it)) },
            )
        }
    }

    if (hasAudioControls) {
        val coupledFootnote = if (hasAudio && hasMultipoint && audio.multipointCoupled) {
            "该型号的 LDAC 与多点连接互斥；切换多点时设备会改用 AAC / SBC。"
        } else {
            null
        }
        AppGroup(
            title = "音频",
            footnote = coupledFootnote,
            footnoteIcon = if (coupledFootnote != null) RoseIcon.INFO else null,
        ) {
            if (hasAudio) {
                val confirmed = (device.knownValue(DeviceField.AUDIO_MODE) as? FieldValue.Text)?.value
                AppChoiceRow(
                    title = "音频模式",
                    valueText = confirmedChoiceValue(DeviceField.AUDIO_MODE, device),
                    options = audio.modes.sortedBy(::semanticLabel)
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
            if (hasSpatialAudio) {
                SettingToggleChoices("空间音频", DeviceField.SPATIAL_AUDIO, DeviceSetting.SPATIAL_AUDIO, device, enabled, onSet)
            }
        }
    }

    if (hasGain || hasProtection) {
        AppGroup(title = "输出与保护") {
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

internal fun confirmedChoiceValue(field: DeviceField, device: DeviceState): String = when (val reading = device.fields[field]) {
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
    icon: RoseIcon? = null,
) {
    val selected = (device.knownValue(field) as? FieldValue.Toggle)?.value
    val isKnown = selected != null
    AppSwitchRow(
        title = label,
        summary = null,
        checked = selected ?: false,
        enabled = enabled && isKnown,
        icon = icon,
        onChange = { requested ->
            if (isKnown && requested != selected) {
                onSet(setting, FieldValue.Toggle(requested))
            }
        },
    )
}

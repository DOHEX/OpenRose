package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.settingPrerequisite

@Composable
internal fun DeviceControlCard(
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
) {
    PromptSettings(state, device, onSet)
    BassBoostSettings(state, device, onSet)
    AudioConnectionSettings(state, device, onSet)
    HearingSettings(state, device, onSet)
}

@Composable
private fun PromptSettings(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val writable = device.capabilities.writableSettings
    val volumeLevels = device.capabilities.promptVolumeLevels
    val hasVolume = DeviceSetting.PROMPT_VOLUME in writable
    val languages = device.capabilities.promptLanguages
    val hasLanguage = DeviceSetting.PROMPT_LANGUAGE in writable
    val hasPrompt = hasVolume || hasLanguage
    if (hasPrompt) {
        AppGroup(title = "提示与音量") {
            if (hasVolume) {
                val confirmed = device.confirmedNumber(DeviceField.PROMPT_VOLUME)
                AppChoiceRow(
                    title = "提示音音量",
                    valueText = confirmedChoiceValue(DeviceField.PROMPT_VOLUME, device),
                    options = volumeLevels.sorted().map { level -> AppOption(level, if (level == 0) "关闭" else "$level 档") },
                    selected = confirmed,
                    enabled = state.canWrite(device, DeviceSetting.PROMPT_VOLUME),
                    pending = state.pendingNumber(device, DeviceSetting.PROMPT_VOLUME),
                    onSelect = { level -> onSet(HeadsetCommand.PromptVolume(level)) },
                )
            }
            if (hasLanguage) {
                val confirmed = device.confirmedText(DeviceField.PROMPT_LANGUAGE)
                AppChoiceRow(
                    title = "提示音语言",
                    valueText = confirmedChoiceValue(DeviceField.PROMPT_LANGUAGE, device),
                    options = languages.sorted().map { AppOption(it, semanticLabel(it)) },
                    selected = confirmed,
                    enabled = state.canWrite(device, DeviceSetting.PROMPT_LANGUAGE),
                    pending = state.pendingText(device, DeviceSetting.PROMPT_LANGUAGE),
                    onSelect = { onSet(HeadsetCommand.PromptLanguage(it)) },
                )
            }
        }
    }
}

@Composable
private fun BassBoostSettings(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val writable = device.capabilities.writableSettings
    val bassLevels = device.capabilities.bassBoostLevels
    val hasBass = DeviceSetting.BASS_BOOST in writable
    if (hasBass) {
        val confirmed = device.confirmedBassBoostLevel
        AppGroup(title = "音效") {
            AppChoiceRow(
                title = "低音增强",
                valueText = confirmed?.let { "$it 档" } ?: "当前状态未知",
                options = bassLevels.sorted().map { AppOption(it, "$it 档") },
                selected = confirmed,
                enabled = state.canWrite(device, DeviceSetting.BASS_BOOST),
                pending = state.pendingNumber(device, DeviceSetting.BASS_BOOST),
                onSelect = { onSet(HeadsetCommand.BassBoost(it)) },
            )
        }
    }
}

@Composable
private fun AudioConnectionSettings(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val writable = device.capabilities.writableSettings
    val audio = device.capabilities.audioControl
    val hasAudio = DeviceSetting.AUDIO_MODE in writable && audio != null
    val hasMultipoint = DeviceSetting.MULTIPOINT in writable
    val hasSpatialAudio = DeviceSetting.SPATIAL_AUDIO in writable
    val hasAudioControls = hasAudio || hasMultipoint || hasSpatialAudio || writable.any { it in additionalAudioSettings }
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
                val confirmed = device.confirmedText(DeviceField.AUDIO_MODE)
                AppChoiceRow(
                    title = "音频模式",
                    valueText = confirmedChoiceValue(DeviceField.AUDIO_MODE, device),
                    options = audio.modes.sortedBy(::semanticLabel)
                        .map { mode -> AppOption(mode, semanticLabel(mode)) },
                    selected = confirmed,
                    enabled = state.canWrite(device, DeviceSetting.AUDIO_MODE),
                    pending = state.pendingText(device, DeviceSetting.AUDIO_MODE),
                    onSelect = { mode -> onSet(HeadsetCommand.AudioMode(mode)) },
                )
            }
            if (hasMultipoint) {
                SettingToggleChoices(
                    "多点连接",
                    DeviceField.MULTIPOINT,
                    HeadsetCommand::Multipoint,
                    device,
                    state.canWrite(device, DeviceSetting.MULTIPOINT),
                    onSet,
                    pendingToggle = state.pendingToggle(device, DeviceSetting.MULTIPOINT),
                )
            }
            AdditionalAudioControls(state, device, onSet)
            if (hasSpatialAudio) {
                SettingToggleChoices(
                    "空间音频",
                    DeviceField.SPATIAL_AUDIO,
                    HeadsetCommand::SpatialAudio,
                    device,
                    state.canWrite(device, DeviceSetting.SPATIAL_AUDIO),
                    onSet,
                    pendingToggle = state.pendingToggle(device, DeviceSetting.SPATIAL_AUDIO),
                )
            }
        }
    }
}

@Composable
private fun HearingSettings(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val writable = device.capabilities.writableSettings
    val gainLevels = device.capabilities.gainLevels
    val hasGain = DeviceSetting.GAIN in writable
    val protectionLevels = device.capabilities.hearingProtectionLevels
    val hasProtection = DeviceSetting.HEARING_PROTECTION in writable
    if (hasGain || hasProtection) {
        AppGroup(title = "输出与保护") {
            if (hasGain) {
                val confirmed = device.confirmedNumber(DeviceField.GAIN)
                AppChoiceRow(
                    title = "增益档位",
                    valueText = confirmedChoiceValue(DeviceField.GAIN, device),
                    options = gainLevels.sorted().map { level -> AppOption(level, gainLevelLabel(level)) },
                    selected = confirmed,
                    enabled = state.canWrite(device, DeviceSetting.GAIN),
                    pending = state.pendingNumber(device, DeviceSetting.GAIN),
                    onSelect = { level -> onSet(HeadsetCommand.Gain(level)) },
                )
            }
            if (hasProtection) {
                val confirmed = device.confirmedNumber(DeviceField.HEARING_PROTECTION)
                AppChoiceRow(
                    title = "听力保护阈值",
                    valueText = confirmedChoiceValue(DeviceField.HEARING_PROTECTION, device),
                    options = protectionLevels.sorted().map { level -> AppOption(level, hearingProtectionLabel(level)) },
                    selected = confirmed,
                    enabled = state.canWrite(device, DeviceSetting.HEARING_PROTECTION),
                    pending = state.pendingNumber(device, DeviceSetting.HEARING_PROTECTION),
                    onSelect = { level -> onSet(HeadsetCommand.HearingProtection(level)) },
                )
            }
        }
    }
}

private val additionalAudioSettings = setOf(
    DeviceSetting.SPATIAL_MODE,
    DeviceSetting.SPATIAL_CONTENT,
    DeviceSetting.EAR_ADAPTIVE,
    DeviceSetting.INDEPENDENT_MIC_ACTIVATION,
    DeviceSetting.INDEPENDENT_MIC_MODE,
)

internal val audioPageSettings = additionalAudioSettings + setOf(
    DeviceSetting.AUDIO_MODE,
    DeviceSetting.MULTIPOINT,
    DeviceSetting.SPATIAL_AUDIO,
    DeviceSetting.PROMPT_VOLUME,
    DeviceSetting.GAIN,
    DeviceSetting.HEARING_PROTECTION,
    DeviceSetting.BASS_BOOST,
    DeviceSetting.PROMPT_LANGUAGE,
)

@Composable
private fun AdditionalAudioControls(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val writable = device.capabilities.writableSettings
    if (DeviceSetting.SPATIAL_MODE in writable) {
        SpatialChoiceRow(
            HeadsetCommand::SpatialMode,
            DeviceField.SPATIAL_MODE,
            DeviceSetting.SPATIAL_MODE,
            device.capabilities.spatialModes,
            state,
            device,
            onSet,
        )
    }
    if (DeviceSetting.SPATIAL_CONTENT in writable && device.settingPrerequisite(DeviceSetting.SPATIAL_CONTENT) == null) {
        SpatialChoiceRow(
            HeadsetCommand::SpatialContent,
            DeviceField.SPATIAL_CONTENT,
            DeviceSetting.SPATIAL_CONTENT,
            device.capabilities.spatialContents,
            state,
            device,
            onSet,
        )
    }
    if (DeviceSetting.EAR_ADAPTIVE in writable) {
        SettingToggleChoices(
            "耳道自适应",
            DeviceField.EAR_ADAPTIVE,
            HeadsetCommand::EarAdaptive,
            device,
            state.canWrite(device, DeviceSetting.EAR_ADAPTIVE),
            onSet,
            pendingToggle = state.pendingToggle(device, DeviceSetting.EAR_ADAPTIVE),
        )
    }
    if (DeviceSetting.INDEPENDENT_MIC_ACTIVATION in writable) {
        SettingToggleChoices(
            "激活独立麦克风",
            DeviceField.INDEPENDENT_MIC_ACTIVATION,
            HeadsetCommand::IndependentMicActivation,
            device,
            state.canWrite(device, DeviceSetting.INDEPENDENT_MIC_ACTIVATION),
            onSet,
            pendingToggle = state.pendingToggle(device, DeviceSetting.INDEPENDENT_MIC_ACTIVATION),
        )
    }
    if (DeviceSetting.INDEPENDENT_MIC_MODE in writable) {
        SettingToggleChoices(
            "独立麦克风模式",
            DeviceField.INDEPENDENT_MIC_MODE,
            HeadsetCommand::IndependentMicMode,
            device,
            state.canWrite(device, DeviceSetting.INDEPENDENT_MIC_MODE) &&
                device.settingPrerequisite(DeviceSetting.INDEPENDENT_MIC_MODE) == null,
            onSet,
            pendingToggle = state.pendingToggle(device, DeviceSetting.INDEPENDENT_MIC_MODE),
        )
    }
}

@Composable
private fun SpatialChoiceRow(
    command: (String) -> HeadsetCommand,
    field: DeviceField,
    setting: DeviceSetting,
    choices: Set<String>,
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
) {
    val confirmed = device.confirmedText(field)
    AppChoiceRow(
        title = field.displayName,
        valueText = confirmedChoiceValue(field, device),
        options = choices.map { AppOption(it, semanticLabel(it)) },
        selected = confirmed,
        enabled = state.canWrite(device, setting),
        pending = state.pendingText(device, setting),
        onSelect = { onSet(command(it)) },
    )
}

internal fun confirmedChoiceValue(field: DeviceField, device: DeviceState): String = when (val reading = device.reading(field)) {
    is FieldReading.Known -> displayFieldValue(field, reading.value, device.profile)
    is FieldReading.Unknown -> "当前状态未知"
    is FieldReading.NotProvided -> "设备未提供"
    FieldReading.Loading, null -> readingText(field, reading, false, device.diagnostics.fieldErrors[field], device.profile)
}

@Composable
internal fun SettingToggleChoices(
    label: String,
    field: DeviceField,
    command: (Boolean) -> HeadsetCommand,
    device: DeviceState,
    enabled: Boolean,
    onSet: (HeadsetCommand) -> Unit,
    icon: RoseIcon? = null,
    pendingToggle: Boolean? = null,
) {
    val selected = device.confirmedToggle(field)
    val isKnown = selected != null
    AppSwitchRow(
        title = label,
        summary = when (pendingToggle) {
            true -> "正在开启"
            false -> "正在关闭"
            null -> if (isKnown) null else "当前状态未知"
        },
        checked = selected ?: false,
        enabled = enabled && isKnown,
        icon = icon,
        onChange = { requested ->
            if (isKnown && requested != selected) {
                onSet(command(requested))
            }
        },
    )
}

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.SettingOperation
import io.github.dohex.openrose.core.SettingStage
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

/** Choices always come from device capabilities; selected values always come from device replies. */
@Composable
internal fun NoiseControlCard(
    state: ControlState,
    device: DeviceState,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
    val writable = device.capabilities.writableSettings
    val ready = state.isReady(device)
    val pending = state.hasPendingSetting(device)
    val enabled = ready && !pending
    val confirmedMode = (device.knownValue(DeviceField.NOISE_MODE) as? FieldValue.Text)?.value

    if (DeviceSetting.NOISE_MODE in writable && capabilities.modes.isNotEmpty()) {
        AppGroup(
            title = "降噪模式",
            footnote = confirmedSettingStatus(DeviceField.NOISE_MODE, device),
        ) {
            val options = capabilities.modes
                .sortedWith(compareBy({ noiseModeOrder(it) }, { it }))
                .map { mode -> AppOption(mode, semanticLabel(mode)) }
            AppRadioGroup(
                options = options,
                selected = confirmedMode,
                enabled = enabled,
                onSelect = { mode ->
                    if (mode != confirmedMode) onSet(DeviceSetting.NOISE_MODE, FieldValue.Text(mode))
                },
            )
        }
    }

    listOf(
        Triple(DeviceSetting.ANC_LEVEL, "降噪档位", capabilities.levelCapabilities(DeviceSetting.ANC_LEVEL)),
        Triple(DeviceSetting.TRANSPARENCY_LEVEL, "通透档位", capabilities.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL)),
    ).forEach { (setting, title, levelCapabilities) ->
        if (setting in writable && levelCapabilities?.isVisibleIn(confirmedMode) == true) {
            val field = when (setting) {
                DeviceSetting.ANC_LEVEL -> DeviceField.ANC_LEVEL
                else -> DeviceField.TRANSPARENCY_LEVEL
            }
            val selected = (device.knownValue(field) as? FieldValue.Number)?.value
            AppGroup(title = title) {
                AppSegmented(
                    options = levelCapabilities.levels.sorted().map { level -> AppOption(level, "$level 档") },
                    selected = selected,
                    enabled = enabled,
                    onSelect = { level ->
                        if (level != selected) onSet(setting, FieldValue.Number(level))
                    },
                )
            }
        }
    }

    if (DeviceSetting.NOISE_CYCLE in writable && capabilities.cycleModes.isNotEmpty()) {
        val flags = (device.knownValue(DeviceField.NOISE_CYCLE) as? FieldValue.NoiseCycle)?.flags
        val cycleKnown = flags != null && capabilities.cycleModes.all { flags[it] != null }
        val selectedCount = capabilities.cycleModes.count { flags?.get(it) == true }
        val footnote = when {
            !cycleKnown -> "尚无完整状态"
            capabilities.minimumCycleSelections > 0 ->
                "按已确认的选择调整轮换，至少保留 ${capabilities.minimumCycleSelections} 项。"
            else -> "按已确认的选择调整轮换，可清空全部选项。"
        }
        AppGroup(title = "降噪轮换", footnote = footnote) {
            AppCheckGroup(
                options = capabilities.cycleModes.map { mode -> AppOption(mode, semanticLabel(mode)) },
                checked = capabilities.cycleModes.filterTo(mutableSetOf()) { flags?.get(it) == true },
                enabledFor = { mode ->
                    val checked = flags?.get(mode) == true
                    val canRemove = !checked || selectedCount > capabilities.minimumCycleSelections
                    enabled && cycleKnown && canRemove
                },
                onToggle = { mode ->
                    val checked = flags?.get(mode) == true
                    val canRemove = !checked || selectedCount > capabilities.minimumCycleSelections
                    if (enabled && cycleKnown && canRemove) {
                        val requested = capabilities.cycleModes.associateWith { option ->
                            if (option == mode) !checked else flags?.get(option) == true
                        }
                        onSet(DeviceSetting.NOISE_CYCLE, FieldValue.NoiseCycle(requested))
                    }
                },
            )
        }
    }
}

private fun noiseModeOrder(mode: String): Int = when (mode) {
    "normal" -> 0
    "anc" -> 1
    "transparency" -> 2
    "wind" -> 3
    "auto" -> 4
    "dynamic" -> 5
    else -> 6
}

internal fun confirmedSettingStatus(field: DeviceField, device: DeviceState): String = when (val reading = device.fields[field]) {
    is FieldReading.Known -> "设备已确认：${displayFieldValue(field, reading.value, device.profile)}"
    is FieldReading.Unknown -> "当前状态：未知"
    is FieldReading.NotProvided -> "当前状态：设备未提供"
    FieldReading.Loading, null -> "当前状态：尚未读取"
}

@Composable
internal fun ConfirmedSettingLabel(label: String, field: DeviceField, device: DeviceState) {
    AppInfoRow(label, confirmedSettingStatus(field, device))
}

/** Detailed request and session history is shown only on the diagnostics page. */
@Composable
internal fun SettingOperationsCard(state: ControlState) {
    val operations = state.settingOperations.takeLast(4).asReversed()
    if (operations.isEmpty()) return
    val sessionId = when (val connection = state.connection) {
        is ConnectionState.Connecting -> connection.sessionId
        is ConnectionState.Connected -> connection.sessionId
        ConnectionState.Disconnected -> null
    }
    AppGroup(title = "设置操作", footnote = "请求值与已确认设备状态分别显示。") {
        operations.forEachIndexed { index, operation ->
            val profile = state.device
                ?.takeIf { it.sessionId == operation.sessionId && operation.sessionId == sessionId }
                ?.profile
            if (index > 0) AppDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("请求 #${operation.id}：${operation.setting.displayName()}", role = AppTextRole.Title)
                AppText("请求值：${operation.requestedValueText(profile)}", role = AppTextRole.Summary)
                AppText(operation.stage.displayText())
                if (operation.setting == DeviceSetting.CUSTOM_EQ) {
                    if (operation.totalFrames > 0) {
                        AppText("发送进度：${operation.writtenFrames}/${operation.totalFrames} 步", role = AppTextRole.Summary)
                    }
                    val modeConfirmed = DeviceField.EQ in operation.confirmedFields
                    val curveConfirmed = DeviceField.CUSTOM_EQ in operation.confirmedFields
                    AppText(
                        when {
                            modeConfirmed && curveConfirmed -> "模式与曲线均已确认"
                            modeConfirmed -> "模式已确认，曲线尚未确认"
                            curveConfirmed -> "曲线已确认，模式尚未确认"
                            else -> "模式与曲线尚未确认"
                        },
                        role = AppTextRole.Summary,
                    )
                }
                operation.error?.let { AppText(it.message, role = AppTextRole.Summary) }
                if (operation.sessionId != sessionId) {
                    AppText("已结束会话 ${operation.sessionId} 的操作记录", role = AppTextRole.Summary)
                }
            }
            if (index < operations.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

internal fun DeviceState.knownValue(field: DeviceField): FieldValue? =
    (fields[field] as? FieldReading.Known)?.value

private val pendingSettingStages = setOf(SettingStage.SUBMITTED, SettingStage.PARTIALLY_WRITTEN, SettingStage.WRITTEN)

internal fun ControlState.isReady(device: DeviceState): Boolean {
    val connection = connection as? ConnectionState.Connected ?: return false
    return connection.phase == ConnectionPhase.READY &&
        connection.deviceId == device.deviceId && connection.sessionId == device.sessionId &&
        platform.transport == TransportAvailability.Available &&
        platform.permission in setOf(PermissionState.GRANTED, PermissionState.NOT_REQUIRED)
}

internal fun ControlState.hasPendingSetting(device: DeviceState): Boolean = settingOperations.any {
    it.sessionId == device.sessionId && it.stage in pendingSettingStages
}

private fun DeviceSetting.displayName(): String = when (this) {
    DeviceSetting.NOISE_MODE -> "降噪模式"
    DeviceSetting.ANC_LEVEL -> "降噪档位"
    DeviceSetting.TRANSPARENCY_LEVEL -> "通透档位"
    DeviceSetting.NOISE_CYCLE -> "降噪轮换"
    DeviceSetting.EQ -> "预设 EQ"
    DeviceSetting.GAME_MODE -> "游戏模式"
    DeviceSetting.TOUCH_BINDING -> "触控绑定"
    DeviceSetting.TOUCH_ENABLED -> "触控总开关"
    DeviceSetting.EAR_DETECTION -> "佩戴检测功能"
    DeviceSetting.AUDIO_MODE -> "音频模式"
    DeviceSetting.MULTIPOINT -> "多点连接"
    DeviceSetting.PROMPT_VOLUME -> "提示音音量"
    DeviceSetting.GAIN -> "增益档位"
    DeviceSetting.HEARING_PROTECTION -> "听力保护阈值"
    DeviceSetting.CUSTOM_EQ -> "自定义 EQ"
}

private fun SettingOperation.requestedValueText(profile: ModelProfile?): String = when (setting) {
    DeviceSetting.NOISE_MODE -> displayFieldValue(DeviceField.NOISE_MODE, requestedValue)
    DeviceSetting.ANC_LEVEL -> displayFieldValue(DeviceField.ANC_LEVEL, requestedValue)
    DeviceSetting.TRANSPARENCY_LEVEL -> displayFieldValue(DeviceField.TRANSPARENCY_LEVEL, requestedValue)
    DeviceSetting.NOISE_CYCLE -> displayFieldValue(DeviceField.NOISE_CYCLE, requestedValue)
    DeviceSetting.EQ -> displayFieldValue(DeviceField.EQ, requestedValue, profile)
    DeviceSetting.GAME_MODE -> displayFieldValue(DeviceField.GAME_MODE, requestedValue)
    DeviceSetting.TOUCH_BINDING -> displayFieldValue(DeviceField.TOUCH_KEYS, requestedValue)
    DeviceSetting.TOUCH_ENABLED -> displayFieldValue(DeviceField.TOUCH_ENABLED, requestedValue)
    DeviceSetting.EAR_DETECTION -> displayFieldValue(DeviceField.EAR_DETECTION, requestedValue)
    DeviceSetting.AUDIO_MODE -> displayFieldValue(DeviceField.AUDIO_MODE, requestedValue)
    DeviceSetting.MULTIPOINT -> displayFieldValue(DeviceField.MULTIPOINT, requestedValue)
    DeviceSetting.PROMPT_VOLUME -> displayFieldValue(DeviceField.PROMPT_VOLUME, requestedValue)
    DeviceSetting.CUSTOM_EQ -> displayFieldValue(DeviceField.CUSTOM_EQ, requestedValue)
    DeviceSetting.GAIN -> displayFieldValue(DeviceField.GAIN, requestedValue)
    DeviceSetting.HEARING_PROTECTION -> displayFieldValue(DeviceField.HEARING_PROTECTION, requestedValue)
}

private fun SettingStage.displayText(): String = when (this) {
    SettingStage.SUBMITTED -> "已提交 · 等待写入"
    SettingStage.PARTIALLY_WRITTEN -> "部分命令已发送 · 等待剩余发送与设备确认"
    SettingStage.WRITTEN -> "底层写入完成 · 等待设备确认"
    SettingStage.CONFIRMED -> "设备已确认"
    SettingStage.NOT_APPLIED -> "设备回读与请求不一致 · 已显示实际返回状态"
    SettingStage.TIMED_OUT -> "设置超时 · 未获完整设备确认"
    SettingStage.DISCONNECTED -> "连接已断开 · 请求未获完整确认"
    SettingStage.FAILED -> "设置失败"
}

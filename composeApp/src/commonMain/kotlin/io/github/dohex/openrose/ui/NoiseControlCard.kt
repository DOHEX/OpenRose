package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
    AppCard {
        AppText("降噪控制", title = true)
        Spacer(Modifier.height(8.dp))
        AppText(
            when {
                !ready -> "控制会话尚未就绪，暂不能设置。"
                pending -> "正在处理设置请求，当前选择保留设备已确认的状态。"
                else -> "选择反映设备已确认的状态，设置结果由设备回报确认。"
            },
            secondary = true,
        )
        if (DeviceSetting.NOISE_MODE in writable && capabilities.modes.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            ConfirmedSettingLabel("模式", DeviceField.NOISE_MODE, device)
            Spacer(Modifier.height(8.dp))
            val selected = (device.knownValue(DeviceField.NOISE_MODE) as? FieldValue.Text)?.value
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                capabilities.modes.forEach { mode ->
                    AppButton(
                        semanticLabel(mode),
                        { if (mode != selected) onSet(DeviceSetting.NOISE_MODE, FieldValue.Text(mode)) },
                        selected = mode == selected,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
        }
        listOf(
            Triple(DeviceSetting.ANC_LEVEL, DeviceField.ANC_LEVEL, "降噪档位"),
            Triple(DeviceSetting.TRANSPARENCY_LEVEL, DeviceField.TRANSPARENCY_LEVEL, "通透档位"),
        ).forEach { (setting, field, label) ->
            if (setting in writable && capabilities.levels.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ConfirmedSettingLabel(label, field, device)
                Spacer(Modifier.height(8.dp))
                val selected = (device.knownValue(field) as? FieldValue.Number)?.value
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    capabilities.levels.sorted().forEach { level ->
                        AppButton(
                            "$level 档",
                            { if (level != selected) onSet(setting, FieldValue.Number(level)) },
                            selected = level == selected,
                            choice = true,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        if (DeviceSetting.NOISE_CYCLE in writable && capabilities.cycleModes.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            AppText("降噪轮换")
            val flags = (device.knownValue(DeviceField.NOISE_CYCLE) as? FieldValue.NoiseCycle)?.flags
            val cycleKnown = flags != null && capabilities.cycleModes.all { flags[it] != null }
            val selectedCount = capabilities.cycleModes.count { flags?.get(it) == true }
            Spacer(Modifier.height(8.dp))
            AppText(
                when {
                    !cycleKnown -> "尚无完整的设备轮换状态，读取确认后才能调整。"
                    capabilities.minimumCycleSelections > 0 ->
                        "按已确认的选择调整轮换，至少保留 ${capabilities.minimumCycleSelections} 项。"
                    else -> "按已确认的选择调整轮换，可清空全部选项。"
                },
                secondary = true,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                capabilities.cycleModes.forEach { mode ->
                    val checked = flags?.get(mode) == true
                    val canRemove = !checked || selectedCount > capabilities.minimumCycleSelections
                    AppButton(
                        semanticLabel(mode),
                        {
                            if (cycleKnown && canRemove) {
                                val requested = capabilities.cycleModes.associateWith { option ->
                                    if (option == mode) !checked else flags[option] == true
                                }
                                onSet(DeviceSetting.NOISE_CYCLE, FieldValue.NoiseCycle(requested))
                            }
                        },
                        selected = checked,
                        choice = true,
                        enabled = enabled && cycleKnown && canRemove,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConfirmedSettingLabel(label: String, field: DeviceField, device: DeviceState) {
    AppText(label)
    AppText(
        when (val reading = device.fields[field]) {
            is FieldReading.Known -> "设备已确认：${displayFieldValue(field, reading.value, device.profile)}"
            is FieldReading.Unknown -> "当前状态：未知"
            is FieldReading.NotProvided -> "当前状态：设备未提供"
            FieldReading.Loading, null -> "当前状态：尚未读取"
        },
        secondary = true,
    )
}

/** Kept outside the connected device card so a disconnected request still has a visible result. */
@Composable
internal fun SettingOperationsCard(state: ControlState) {
    val operations = state.settingOperations.takeLast(4).asReversed()
    if (operations.isEmpty()) return
    val sessionId = when (val connection = state.connection) {
        is ConnectionState.Connecting -> connection.sessionId
        is ConnectionState.Connected -> connection.sessionId
        ConnectionState.Disconnected -> null
    }
    AppCard {
        AppText("设置操作", title = true)
        Spacer(Modifier.height(8.dp))
        AppText("请求值与已确认设备状态分别显示。", secondary = true)
        operations.forEach { operation ->
            val profile = state.device?.takeIf { it.sessionId == operation.sessionId && operation.sessionId == sessionId }?.profile
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("请求 #${operation.id}：${operation.setting.displayName()}")
                AppText("请求值：${operation.requestedValueText(profile)}", secondary = true)
                AppText(operation.stage.displayText())
                operation.error?.let { AppText(it.message, secondary = true) }
                if (operation.sessionId != sessionId) {
                    AppText("已结束会话 ${operation.sessionId} 的操作记录", secondary = true)
                }
            }
        }
    }
}

internal fun DeviceState.knownValue(field: DeviceField): FieldValue? =
    (fields[field] as? FieldReading.Known)?.value

private val pendingSettingStages = setOf(SettingStage.SUBMITTED, SettingStage.WRITTEN)

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
    else -> name
}

private fun SettingOperation.requestedValueText(profile: ModelProfile?): String = when (setting) {
    DeviceSetting.NOISE_MODE -> displayFieldValue(DeviceField.NOISE_MODE, requestedValue)
    DeviceSetting.ANC_LEVEL -> displayFieldValue(DeviceField.ANC_LEVEL, requestedValue)
    DeviceSetting.TRANSPARENCY_LEVEL -> displayFieldValue(DeviceField.TRANSPARENCY_LEVEL, requestedValue)
    DeviceSetting.NOISE_CYCLE -> displayFieldValue(DeviceField.NOISE_CYCLE, requestedValue)
    DeviceSetting.EQ -> displayFieldValue(DeviceField.EQ, requestedValue, profile)
    DeviceSetting.GAME_MODE -> displayFieldValue(DeviceField.GAME_MODE, requestedValue)
    else -> requestedValue.displayText
}

private fun SettingStage.displayText(): String = when (this) {
    SettingStage.SUBMITTED -> "已提交 · 等待写入"
    SettingStage.WRITTEN -> "底层写入完成 · 等待设备确认"
    SettingStage.CONFIRMED -> "设备已确认"
    SettingStage.NOT_APPLIED -> "设备回读与请求不一致 · 已显示实际返回状态"
    SettingStage.TIMED_OUT -> "设置超时 · 未获设备确认"
    SettingStage.DISCONNECTED -> "连接已断开 · 请求未获确认"
    SettingStage.FAILED -> "设置失败"
}

package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.DeviceField
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
    if (!hasAudio && !hasMultipoint && !hasVolume) return
    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    AppCard {
        AppText("音频与提示音", title = true)
        Spacer(Modifier.height(8.dp))
        SettingControlsSummary(state, device)
        if (hasAudio) {
            Spacer(Modifier.height(20.dp))
            ConfirmedSettingLabel("音频模式", DeviceField.AUDIO_MODE, device)
            Spacer(Modifier.height(8.dp))
            val selected = (device.knownValue(DeviceField.AUDIO_MODE) as? FieldValue.Text)?.value
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                audio.modes.forEach { mode ->
                    AppButton(
                        semanticLabel(mode),
                        { if (mode != selected) onSet(DeviceSetting.AUDIO_MODE, FieldValue.Text(mode)) },
                        selected = mode == selected,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            AppText("这里只设置耳机音频偏好，不表示系统音频链路已协商该编码。", secondary = true)
        }
        if (hasMultipoint) {
            Spacer(Modifier.height(20.dp))
            SettingToggleChoices("多点连接", DeviceField.MULTIPOINT, DeviceSetting.MULTIPOINT, device, enabled, onSet)
        }
        if (hasAudio && hasMultipoint) {
            Spacer(Modifier.height(8.dp))
            AppText(
                if (audio.multipointCoupled) {
                    "选择 LDAC 会关闭多点连接；开启多点连接会切换到 AAC / SBC。"
                } else {
                    "音频模式与多点连接分别设置，实际可用组合以耳机与连接设备为准。"
                },
                secondary = true,
            )
        }
        if (hasVolume) {
            Spacer(Modifier.height(20.dp))
            ConfirmedSettingLabel("提示音音量", DeviceField.PROMPT_VOLUME, device)
            Spacer(Modifier.height(8.dp))
            val selected = (device.knownValue(DeviceField.PROMPT_VOLUME) as? FieldValue.Number)?.value
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                volumeLevels.sorted().forEach { level ->
                    AppButton(
                        "$level 档",
                        { if (level != selected) onSet(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level)) },
                        selected = level == selected,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingControlsSummary(state: ControlState, device: DeviceState) {
    AppText(
        when {
            !state.isReady(device) -> "控制会话尚未就绪，暂不能设置。"
            state.hasPendingSetting(device) -> "正在处理设置请求，当前选择保留设备已确认的状态。"
            else -> "选择反映设备已确认的状态，设置结果由设备回报确认。"
        },
        secondary = true,
    )
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
    ConfirmedSettingLabel(label, field, device)
    Spacer(Modifier.height(8.dp))
    val selected = (device.knownValue(field) as? FieldValue.Toggle)?.value
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(true, false).forEach { requested ->
            AppButton(
                if (requested) "开启" else "关闭",
                { if (requested != selected) onSet(setting, FieldValue.Toggle(requested)) },
                selected = requested == selected,
                choice = true,
                enabled = enabled,
            )
        }
    }
}

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
import io.github.dohex.openrose.core.protocol.ModelProfile

@Composable
internal fun EqualizerControlCard(
    state: ControlState,
    device: DeviceState,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val presets = device.capabilities.equalizer?.presets.orEmpty()
    val hasEqualizer = DeviceSetting.EQ in device.capabilities.writableSettings && presets.isNotEmpty()
    val hasGameMode = DeviceSetting.GAME_MODE in device.capabilities.writableSettings
    if (!hasEqualizer && !hasGameMode) return
    val ready = state.isReady(device)
    val pending = state.hasPendingSetting(device)
    val enabled = ready && !pending
    AppCard {
        AppText("音效与游戏", title = true)
        Spacer(Modifier.height(8.dp))
        AppText(
            when {
                !ready -> "控制会话尚未就绪，暂不能设置。"
                pending -> "正在处理设置请求，当前选择保留设备已确认的状态。"
                else -> "选择反映设备已确认的状态，设置结果由设备回报确认。"
            },
            secondary = true,
        )
        if (hasEqualizer) {
            Spacer(Modifier.height(20.dp))
            ConfirmedSettingLabel("预设 EQ", DeviceField.EQ, device)
            Spacer(Modifier.height(8.dp))
            val selected = (device.knownValue(DeviceField.EQ) as? FieldValue.Text)?.value
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    AppButton(
                        equalizerLabel(preset, device.profile),
                        { if (preset != selected) onSet(DeviceSetting.EQ, FieldValue.Text(preset)) },
                        selected = preset == selected,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
            if (device.profile == ModelProfile.I7_RS_V2 && "eap" !in presets) {
                Spacer(Modifier.height(8.dp))
                AppText("EAP 仅在设备固件满足型号要求后开放。", secondary = true)
            }
        }
        if (hasGameMode) {
            Spacer(Modifier.height(20.dp))
            ConfirmedSettingLabel("游戏模式", DeviceField.GAME_MODE, device)
            Spacer(Modifier.height(8.dp))
            val selected = (device.knownValue(DeviceField.GAME_MODE) as? FieldValue.Toggle)?.value
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(true, false).forEach { requested ->
                    AppButton(
                        if (requested) "开启" else "关闭",
                        { if (requested != selected) onSet(DeviceSetting.GAME_MODE, FieldValue.Toggle(requested)) },
                        selected = requested == selected,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

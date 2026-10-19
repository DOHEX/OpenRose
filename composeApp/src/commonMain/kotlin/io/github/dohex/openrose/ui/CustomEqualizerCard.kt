package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue

@Composable
internal fun CustomEqualizerCard(
    state: ControlState,
    device: DeviceState,
    draft: List<Int>?,
    onDraftChange: (List<Int>) -> Unit,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val capabilities = device.capabilities.equalizer?.custom ?: return
    if (DeviceSetting.CUSTOM_EQ !in device.capabilities.writableSettings) return
    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    val confirmed = (device.knownValue(DeviceField.CUSTOM_EQ) as? FieldValue.IntegerList)?.values
    val confirmedValid = confirmed != null && confirmed.size == capabilities.bandsHz.size &&
        confirmed.all { it in capabilities.minGain..capabilities.maxGain }
    val draftValid = draft != null && draft.size == capabilities.bandsHz.size &&
        draft.all { it in capabilities.minGain..capabilities.maxGain }
    AppCard {
        AppText("自定义十段 EQ", title = true)
        Spacer(Modifier.height(8.dp))
        SettingControlsSummary(state, device)
        Spacer(Modifier.height(20.dp))
        ConfirmedSettingLabel("设备 EQ 模式", DeviceField.EQ, device)
        Spacer(Modifier.height(12.dp))
        ConfirmedSettingLabel("设备曲线", DeviceField.CUSTOM_EQ, device)
        Spacer(Modifier.height(20.dp))
        AppText("本次会话草稿", title = true)
        Spacer(Modifier.height(8.dp))
        AppText("编辑草稿不会改变设备状态。应用后需分别确认模式与曲线，才算整项完成。", secondary = true)
        if (confirmedValid) {
            Spacer(Modifier.height(12.dp))
            AppButton(
                if (draft == null) "复制设备曲线为草稿" else "用设备曲线替换草稿",
                { onDraftChange(confirmed.toList()) },
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(12.dp))
        AppButton(
            if (draft == null) "从零创建草稿" else "草稿清零",
            { onDraftChange(List(capabilities.bandsHz.size) { 0 }) },
            enabled = enabled,
        )
        if (draft == null) {
            Spacer(Modifier.height(12.dp))
            AppText("尚未创建草稿。零值草稿不代表设备当前曲线。", secondary = true)
        } else if (!draftValid) {
            Spacer(Modifier.height(12.dp))
            AppText("草稿不符合当前型号的长度或范围，暂不能应用。", secondary = true)
        } else {
            Spacer(Modifier.height(12.dp))
            AppText("以下为未确认的草稿值（${capabilities.minGain}～${capabilities.maxGain}），不会被回读自动覆盖。", secondary = true)
            capabilities.bandsHz.forEachIndexed { index, hz ->
                val value = draft[index]
                val frequency = if (hz >= 1000 && hz % 1000 == 0) "${hz / 1000} kHz" else "$hz Hz"
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(frequency, Modifier.weight(1f))
                    AppButton(
                        "−",
                        { onDraftChange(draft.toMutableList().apply { this[index] = value - 1 }) },
                        enabled = enabled && value > capabilities.minGain,
                        contentDescription = "降低 $frequency 草稿值",
                    )
                    AppText(value.toString(), Modifier.semantics { contentDescription = "$frequency 草稿值" })
                    AppButton(
                        "+",
                        { onDraftChange(draft.toMutableList().apply { this[index] = value + 1 }) },
                        enabled = enabled && value < capabilities.maxGain,
                        contentDescription = "增加 $frequency 草稿值",
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            AppButton(
                "应用草稿到设备",
                { onSet(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(draft.toList())) },
                enabled = enabled,
            )
        }
    }
}

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
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue

@Composable
internal fun TouchControlCard(
    state: ControlState,
    device: DeviceState,
    selectedSlot: TouchSlot?,
    onSlotChange: (TouchSlot) -> Unit,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val writable = device.capabilities.writableSettings
    val touch = device.capabilities.touchControl
    val hasBindings = DeviceSetting.TOUCH_BINDING in writable && touch != null &&
        touch.slots.isNotEmpty() && touch.actions.isNotEmpty()
    val hasTouchSwitch = DeviceSetting.TOUCH_ENABLED in writable
    val hasDetection = DeviceSetting.EAR_DETECTION in writable
    if (!hasBindings && !hasTouchSwitch && !hasDetection) return
    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    AppCard {
        AppText("触控与检测", title = true)
        Spacer(Modifier.height(8.dp))
        SettingControlsSummary(state, device)
        if (hasBindings) {
            val slot = selectedSlot?.takeIf { it in touch.slots } ?: touch.slots.first()
            val binding = (device.knownValue(DeviceField.TOUCH_KEYS) as? FieldValue.TouchKeys)?.bindings
                ?.firstOrNull { it.side == slot.side && it.gesture == slot.gesture }
            Spacer(Modifier.height(20.dp))
            AppText("触控绑定")
            Spacer(Modifier.height(8.dp))
            AppText("以下操作修改触控绑定，点击动作不会立即执行播放或调音量。", secondary = true)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                touch.slots.map { it.side }.distinct().forEach { side ->
                    AppButton(
                        touchSideLabel(side),
                        {
                            onSlotChange(
                                touch.slots.firstOrNull { it.side == side && it.gesture == slot.gesture }
                                    ?: touch.slots.first { it.side == side },
                            )
                        },
                        selected = side == slot.side,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                touch.slots.filter { it.side == slot.side }.forEach { target ->
                    AppButton(
                        touchGestureLabel(target.gesture),
                        { onSlotChange(target) },
                        selected = target == slot,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val action = binding?.action
            AppText(
                if (action == null) "${touchSideLabel(slot.side)}${touchGestureLabel(slot.gesture)}：当前绑定未知，可选择动作后等待设备确认。"
                else "设备已确认：${touchSideLabel(slot.side)}${touchGestureLabel(slot.gesture)}绑定为${semanticLabel(action)}",
                secondary = true,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                touch.actions.forEach { requested ->
                    AppButton(
                        semanticLabel(requested),
                        {
                            if (requested != action) {
                                onSet(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment(slot.side, slot.gesture, requested))
                            }
                        },
                        selected = requested == action,
                        choice = true,
                        enabled = enabled,
                    )
                }
            }
        }
        if (hasTouchSwitch) {
            Spacer(Modifier.height(20.dp))
            SettingToggleChoices("触控总开关", DeviceField.TOUCH_ENABLED, DeviceSetting.TOUCH_ENABLED, device, enabled, onSet)
        }
        if (hasDetection) {
            Spacer(Modifier.height(20.dp))
            SettingToggleChoices("佩戴检测功能", DeviceField.EAR_DETECTION, DeviceSetting.EAR_DETECTION, device, enabled, onSet)
            Spacer(Modifier.height(8.dp))
            AppText("此开关仅配置检测功能，不表示当前佩戴、摘下或入盒状态。", secondary = true)
        }
    }
}

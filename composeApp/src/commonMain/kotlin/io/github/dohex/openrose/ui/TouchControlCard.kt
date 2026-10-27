package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.TouchBinding

@Composable
internal fun TouchControlCard(
    state: ControlState,
    device: DeviceState,
    selectedSlot: TouchSlot?,
    onSlotChange: (TouchSlot?) -> Unit,
    onSet: (DeviceSetting, FieldValue) -> Unit,
    selectedSide: String? = null,
    onSideChange: (String) -> Unit = {},
) {
    val writable = device.capabilities.writableSettings
    val touch = device.capabilities.touchControl
    val touchActions = touch?.actions.orEmpty()
    val slots = touch?.slots.orEmpty().sortedWith(
        compareBy<TouchSlot>({ touchSideOrder(it.side) }, { touchGestureOrder(it.gesture) }, { it.gesture }),
    )
    val hasBindings = DeviceSetting.TOUCH_BINDING in writable && slots.isNotEmpty() && touchActions.isNotEmpty()
    val hasTouchSwitch = DeviceSetting.TOUCH_ENABLED in writable
    val hasDetection = DeviceSetting.EAR_DETECTION in writable
    if (!hasBindings && !hasTouchSwitch && !hasDetection) return

    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    val knownBindings = device.knownValue(DeviceField.TOUCH_KEYS) as? FieldValue.TouchKeys
    val availableSides = slots.map { it.side }.distinct()
    val side = selectedSide?.takeIf { it in availableSides } ?: availableSides.firstOrNull()

    if (hasTouchSwitch || hasDetection) {
        AppGroup(
            title = "功能开关",
            footnote = if (hasDetection) "这里只配置检测功能，不代表当前佩戴、摘下或入盒状态。" else null,
        ) {
            if (hasTouchSwitch) {
                SettingToggleChoices("触控总开关", DeviceField.TOUCH_ENABLED, DeviceSetting.TOUCH_ENABLED, device, enabled, onSet)
            }
            if (hasDetection) {
                SettingToggleChoices("佩戴检测功能", DeviceField.EAR_DETECTION, DeviceSetting.EAR_DETECTION, device, enabled, onSet)
            }
        }
    }

    if (hasBindings && side != null) {
        val sideSlots = slots.filter { it.side == side }
        AppGroup(
            title = "左右耳手势",
            footnote = "修改的是触控绑定，不会立即执行播放、切歌或调节音量。",
        ) {
            AppViewTabs(
                tabs = availableSides.map(::touchSideLabel),
                index = availableSides.indexOf(side).coerceAtLeast(0),
                onSelect = { index ->
                    availableSides.getOrNull(index)?.let { newSide ->
                        onSlotChange(null)
                        onSideChange(newSide)
                    }
                },
            )
            sideSlots.forEach { slot ->
                val binding = knownBindings?.bindings?.firstOrNull {
                    it.side == slot.side && it.gesture == slot.gesture
                }
                val valueText = touchBindingStatus(device.fields[DeviceField.TOUCH_KEYS], binding)
                val confirmedAction = binding?.action
                AppChoiceRow(
                    title = touchGestureLabel(slot.gesture),
                    valueText = valueText,
                    options = touchActions.sortedBy(::semanticLabel)
                        .map { action -> AppOption(action, semanticLabel(action)) },
                    selected = confirmedAction,
                    enabled = enabled,
                    onSelect = { action ->
                        if (enabled && action != confirmedAction) {
                            onSet(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment(slot.side, slot.gesture, action))
                        }
                        onSlotChange(null)
                    },
                    id = "${slot.side}:${slot.gesture}",
                    open = selectedSlot == slot,
                    onOpenChange = { open ->
                        onSlotChange(
                            when {
                                open -> slot
                                selectedSlot == slot -> null
                                else -> selectedSlot
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun touchBindingStatus(
    reading: FieldReading?,
    binding: TouchBinding?,
): String = when {
    reading !is FieldReading.Known -> "当前绑定未知"
    binding == null -> "设备未返回此手势"
    binding.action == null -> "未知动作（${binding.rawAction}）"
    else -> binding.action?.let(::semanticLabel) ?: "当前绑定未知"
}

private fun touchSideOrder(side: String): Int = when (side) {
    "left" -> 0
    "right" -> 1
    else -> 2
}

private fun touchGestureOrder(gesture: String): Int = when (gesture) {
    "single" -> 0
    "double" -> 1
    "triple" -> 2
    "long" -> 3
    else -> 4
}

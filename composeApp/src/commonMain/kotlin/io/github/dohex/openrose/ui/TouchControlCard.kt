package io.github.dohex.openrose.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        AppGroup(title = "功能开关") {
            if (hasTouchSwitch) {
                SettingToggleChoices("触控总开关", DeviceField.TOUCH_ENABLED, DeviceSetting.TOUCH_ENABLED, device, enabled, onSet)
            }
            if (hasDetection) {
                SettingToggleChoices("佩戴检测功能", DeviceField.EAR_DETECTION, DeviceSetting.EAR_DETECTION, device, enabled, onSet)
            }
        }
    }

    if (hasBindings && side != null) {
        AppGroup(
            title = "左右耳手势",
            header = if (availableSides.size > 1) {
                {
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
                }
            } else null,
        ) {
            AnimatedContent(
                targetState = side,
                transitionSpec = {
                    val targetIndex = availableSides.indexOf(targetState)
                    val initialIndex = availableSides.indexOf(initialState)
                    val direction = if (targetIndex >= initialIndex) 1 else -1
                    val slideSpring = spring<IntOffset>(
                        dampingRatio = 0.82f,
                        stiffness = 380f,
                    )
                    val fadeSpring = spring<Float>(
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    (slideInHorizontally(slideSpring) { width -> direction * width / 5 } + fadeIn(fadeSpring))
                        .togetherWith(
                            slideOutHorizontally(slideSpring) { width -> -direction * width / 5 } + fadeOut(fadeSpring)
                        ).using(SizeTransform(clip = false))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { currentSide ->
                val currentSlots = slots.filter { it.side == currentSide }
                Column(Modifier.fillMaxWidth()) {
                    currentSlots.forEach { slot ->
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

package io.github.dohex.openrose.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.InterfaceStyle
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
        val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
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
                        dampingRatio = if (isMaterial3) 0.75f else 0.82f,
                        stiffness = 380f,
                    )
                    val fadeSpring = spring<Float>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isMaterial3) Modifier.padding(horizontal = 16.dp) else Modifier),
                    verticalArrangement = if (isMaterial3) Arrangement.spacedBy(8.dp) else Arrangement.spacedBy(0.dp),
                ) {
                    currentSlots.forEach { slot ->
                        val binding = knownBindings?.bindings?.firstOrNull {
                            it.side == slot.side && it.gesture == slot.gesture
                        }
                        val valueText = touchBindingStatus(device.fields[DeviceField.TOUCH_KEYS], binding)
                        val confirmedAction = binding?.action
                        val isOpen = selectedSlot == slot

                        val rowContent: @Composable () -> Unit = {
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
                                open = isOpen,
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

                        if (isMaterial3) {
                            val containerColor by animateColorAsState(
                                targetValue = if (isOpen) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                                label = "slotContainerColor",
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(containerColor)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isOpen) 0.5f else 0.25f),
                                        shape = RoundedCornerShape(16.dp),
                                    ),
                            ) {
                                CompositionLocalProvider(LocalInDashboardCard provides true) {
                                    rowContent()
                                }
                            }
                        } else {
                            rowContent()
                        }
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

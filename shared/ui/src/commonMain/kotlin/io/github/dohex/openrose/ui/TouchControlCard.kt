@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.TouchBinding
import io.github.dohex.openrose.core.writableNoiseCycle

@Composable
internal fun TouchControlCard(
    state: ControlState,
    device: DeviceState,
    selectedSlot: TouchSlot?,
    onSlotChange: (TouchSlot?) -> Unit,
    onSet: (HeadsetCommand) -> Unit,
    selectedSide: String? = null,
    onSideChange: (String) -> Unit = {},
) {
    val writable = device.capabilities.writableSettings
    val hasTouchSwitch = DeviceSetting.TOUCH_ENABLED in writable
    val hasDetection = DeviceSetting.EAR_DETECTION in writable

    if (hasTouchSwitch || hasDetection) {
        AppGroup(title = "功能开关") {
            if (hasTouchSwitch) {
                SettingToggleChoices(
                    "触控总开关",
                    DeviceField.TOUCH_ENABLED,
                    HeadsetCommand::TouchEnabled,
                    device,
                    state.canWrite(device, DeviceSetting.TOUCH_ENABLED),
                    onSet,
                    pendingToggle = state.pendingToggle(device, DeviceSetting.TOUCH_ENABLED),
                )
            }
            if (hasDetection) {
                SettingToggleChoices(
                    "佩戴检测功能",
                    DeviceField.EAR_DETECTION,
                    HeadsetCommand::EarDetection,
                    device,
                    state.canWrite(device, DeviceSetting.EAR_DETECTION),
                    onSet,
                    pendingToggle = state.pendingToggle(device, DeviceSetting.EAR_DETECTION),
                )
            }
        }
    }

    TouchBindingGroup(state, device, selectedSide, onSideChange, selectedSlot, onSlotChange, onSet)
    TapControlGroup(state, device, onSet)
    device.capabilities.writableNoiseCycle?.let { noise ->
        AppGroup {
            NoiseCycleRow(state, device, noise, onSet)
        }
    }
}

@Composable
private fun NoiseCycleRow(
    state: ControlState,
    device: DeviceState,
    capabilities: NoiseControlCapabilities,
    onSet: (HeadsetCommand) -> Unit,
) {
    val enabled = state.canWrite(device, DeviceSetting.NOISE_CYCLE)
    val flags = (device.knownValue(DeviceField.NOISE_CYCLE) as? FieldValue.NoiseCycle)?.flags
    val cycleKnown = flags != null && capabilities.cycleModes.all { flags[it] != null }
    val cycleModes = remember(capabilities.cycleModes) {
        capabilities.cycleModes.sortedWith(compareBy({ NoiseMode.displayOrder(it) }, { it }))
            .map { mode -> AppOption(mode, noiseModeLabel(mode)) }
    }
    val checked = remember(cycleModes, flags) {
        cycleModes.mapNotNullTo(mutableSetOf()) { it.value.takeIf { mode -> flags?.get(mode) == true } }
    }
    val minimum = capabilities.minimumCycleSelections
    val atMinimum = checked.size <= minimum
    val cycleOptions = remember(cycleModes, checked, atMinimum, minimum) {
        cycleModes.map { if (atMinimum && it.value in checked) it.copy(disabledReason = "至少保留 $minimum 项") else it }
    }
    val summary = remember(cycleKnown, cycleOptions, checked) {
        when {
            !cycleKnown -> "尚未读取完整状态"
            checked.isEmpty() -> "未选择轮换模式"
            else -> cycleOptions.filter { it.value in checked }.joinToString("、") { it.label }
        }
    }
    AppMultiChoiceRow(
        title = "降噪轮换",
        summary = summary,
        options = cycleOptions,
        checked = checked,
        enabled = enabled && cycleKnown,
        enabledFor = { mode -> mode !in checked || !atMinimum },
        onToggle = { mode ->
            val requested = capabilities.cycleModes.associateWith { option ->
                if (option == mode) option !in checked else option in checked
            }
            onSet(HeadsetCommand.NoiseCycle(requested))
        },
    )
}

private fun touchBindingStatus(
    reading: FieldReading?,
    binding: TouchBinding?,
): String {
    if (reading !is FieldReading.Known) return "当前绑定未知"
    if (binding == null) return "设备未返回此手势"
    val action = binding.action ?: return "未知动作（${binding.rawAction}）"
    return semanticLabel(action)
}

private fun ControlState.pendingBinding(device: DeviceState, setting: DeviceSetting, side: String, gesture: String): String? {
    val assignment = pendingValue<FieldValue.TouchAssignment>(device, setting)
    return assignment?.takeIf { it.side == side && it.gesture == gesture }?.action
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

@Composable
private fun TouchBindingRow(
    state: ControlState,
    device: DeviceState,
    touch: TouchControlCapabilities,
    slot: TouchSlot,
    selectedSlot: TouchSlot?,
    onSet: (HeadsetCommand) -> Unit,
    onSlotChange: (TouchSlot?) -> Unit,
) {
    val touchActions = touch.actions
    val knownBindings = device.knownValue(DeviceField.TOUCH_KEYS) as? FieldValue.TouchKeys
    val binding = knownBindings?.bindings?.firstOrNull {
        it.side == slot.side && it.gesture == slot.gesture
    }
    val valueText = touchBindingStatus(device.reading(DeviceField.TOUCH_KEYS), binding)
    val confirmedAction = binding?.action
    val isOpen = selectedSlot == slot

    ContainedBindingRow(isOpen) {
        AppChoiceRow(
            title = touch.slotLabels[slot]?.let { "$it${touchGestureLabel(slot.gesture)}" }
                ?: touchGestureLabel(slot.gesture),
            valueText = valueText,
            options = touchActions.sortedBy(::semanticLabel)
                .map { action -> AppOption(action, semanticLabel(action)) },
            selected = confirmedAction,
            enabled = state.canWrite(device, DeviceSetting.TOUCH_BINDING),
            pending = state.pendingBinding(device, DeviceSetting.TOUCH_BINDING, slot.side, slot.gesture),
            onSelect = { action ->
                onSet(HeadsetCommand.TouchBinding(slot.side, slot.gesture, action))
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
}

@Composable
private fun ContainedBindingRow(isOpen: Boolean, content: @Composable () -> Unit) {
    if (LocalInterfaceStyle.current != InterfaceStyle.MATERIAL3) {
        content()
        return
    }
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
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isOpen) 0.5f else 0.25f),
                shape = shape,
            ),
    ) {
        CompositionLocalProvider(LocalRowPlacement provides RowPlacement.Contained) {
            content()
        }
    }
}

@Composable
private fun TapControlGroup(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    device.capabilities.tapControl?.let { tap ->
        val reading = device.reading(DeviceField.TAP_KEYS)
        val bindings = (device.knownValue(DeviceField.TAP_KEYS) as? FieldValue.TouchKeys)?.bindings.orEmpty()
        var openSlot by remember(device.sessionId) { mutableStateOf<TouchSlot?>(null) }
        AppGroup(title = "轻拍控制") {
            SettingToggleChoices(
                "轻拍开关",
                DeviceField.TAP_ENABLED,
                HeadsetCommand::TapEnabled,
                device,
                state.canWrite(device, DeviceSetting.TAP_ENABLED),
                onSet,
                pendingToggle = state.pendingToggle(device, DeviceSetting.TAP_ENABLED),
            )
            tap.slots.sortedWith(compareBy({ touchSideOrder(it.side) }, { touchGestureOrder(it.gesture) })).forEach { slot ->
                val binding = bindings.firstOrNull { it.side == slot.side && it.gesture == slot.gesture }
                ContainedBindingRow(isOpen = openSlot == slot) {
                    AppChoiceRow(
                        title = touchSlotLabel(slot.side, slot.gesture, tap),
                        valueText = touchBindingStatus(reading, binding),
                        options = tap.actions.sortedBy(::semanticLabel).map { AppOption(it, semanticLabel(it)) },
                        selected = binding?.action,
                        enabled = state.canWrite(device, DeviceSetting.TAP_BINDING),
                        pending = state.pendingBinding(device, DeviceSetting.TAP_BINDING, slot.side, slot.gesture),
                        id = "tap:${slot.side}:${slot.gesture}",
                        open = openSlot == slot,
                        onOpenChange = { openSlot = if (it) slot else null },
                        onSelect = { action ->
                            onSet(HeadsetCommand.TapBinding(slot.side, slot.gesture, action))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchBindingGroup(
    state: ControlState,
    device: DeviceState,
    selectedSide: String?,
    onSideChange: (String) -> Unit,
    selectedSlot: TouchSlot?,
    onSlotChange: (TouchSlot?) -> Unit,
    onSet: (HeadsetCommand) -> Unit,
) {
    if (DeviceSetting.TOUCH_BINDING !in device.capabilities.writableSettings) return
    val touch = device.capabilities.touchControl ?: return
    val slots = touch.slots.sortedWith(
        compareBy<TouchSlot>({ touchSideOrder(it.side) }, { touchGestureOrder(it.gesture) }, { it.gesture }),
    )
    val availableSides = slots.map { it.side }.distinct()
    val side = selectedSide?.takeIf { it in availableSides } ?: availableSides.firstOrNull() ?: return
    val isMaterial3 = LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3
    AppGroup(
        title = if (touch.slotLabels.isEmpty()) "左右耳手势" else "按键手势",
        header = if (availableSides.size > 1) {
            {
                TouchSideTabs(availableSides, side, slots, touch.slotLabels) { newSide ->
                    onSlotChange(null)
                    onSideChange(newSide)
                }
            }
        } else {
            null
        },
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
                        slideOutHorizontally(slideSpring) { width -> -direction * width / 5 } + fadeOut(fadeSpring),
                    ).using(SizeTransform(clip = false))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { currentSide ->
            val currentSlots = slots.filter { it.side == currentSide }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = if (isMaterial3) Arrangement.spacedBy(ListItemDefaults.SegmentedGap) else Arrangement.spacedBy(0.dp),
            ) {
                currentSlots.forEach { slot ->
                    TouchBindingRow(state, device, touch, slot, selectedSlot, onSet, onSlotChange)
                }
            }
        }
    }
}

@Composable
private fun TouchSideTabs(
    availableSides: List<String>,
    side: String,
    slots: List<TouchSlot>,
    labels: Map<TouchSlot, String>,
    onSelect: (String) -> Unit,
) {
    AppViewTabs(
        tabs = availableSides.map { tabSide ->
            slots.firstNotNullOfOrNull { slot ->
                if (slot.side == tabSide) labels[slot] else null
            } ?: touchSideLabel(tabSide)
        },
        index = availableSides.indexOf(side).coerceAtLeast(0),
        onSelect = { index ->
            availableSides.getOrNull(index)?.let { newSide ->
                onSelect(newSide)
            }
        },
    )
}

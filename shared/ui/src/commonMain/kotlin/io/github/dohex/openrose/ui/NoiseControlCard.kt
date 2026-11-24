package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.DeviceField

@Composable
internal fun NoiseControlCard(
    state: ControlState,
    device: DeviceState,
    showLevelControls: Boolean = true,
    onSet: (HeadsetCommand) -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
    val modesEnabled = state.canChooseNoiseMode(device)
    val confirmedMode = device.confirmedNoiseMode?.id
    val pendingMode = state.pendingText(device, DeviceSetting.NOISE_MODE)

    val modes = remember(capabilities.modes) {
        device.noiseModes.sortedBy(NoiseMode::ordinal).map(NoiseMode::id)
            .map { mode -> AppOption(mode, noiseModeLabel(mode)) }
    }
    AppDashboardCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("降噪模式", Modifier.weight(1f), role = AppTextRole.Title)
            val headerStatus = when {
                pendingMode != null -> "等待设备确认"
                confirmedMode == null -> "当前状态未知"
                else -> null
            }
            headerStatus?.let { AppText(it, role = AppTextRole.Summary) }
        }
        NoiseModeChoices(modes, confirmedMode, pendingMode, modesEnabled) { mode ->
            if (mode != confirmedMode) NoiseMode.fromId(mode)?.let { onSet(HeadsetCommand.NoiseMode(it)) }
        }
        if (showLevelControls) {
            NoiseLevelControls(state, device, capabilities, confirmedMode, onSet)
        }
    }
}

@Composable
internal fun NoiseLevelControls(
    state: ControlState,
    device: DeviceState,
    capabilities: NoiseControlCapabilities,
    confirmedMode: String?,
    onSet: (HeadsetCommand) -> Unit,
) {
    val writable = device.capabilities.writableSettings
    val levelControls = remember(capabilities) {
        listOf(
            Triple(DeviceSetting.ANC_LEVEL, "降噪档位", capabilities.ancLevel),
            Triple(DeviceSetting.TRANSPARENCY_LEVEL, "通透档位", capabilities.transparencyLevel),
        )
    }
    levelControls.forEach { (setting, title, levelCapabilities) ->
        if (setting in writable && levelCapabilities?.isVisibleIn(confirmedMode) == true) {
            val field = if (setting == DeviceSetting.ANC_LEVEL) DeviceField.ANC_LEVEL else DeviceField.TRANSPARENCY_LEVEL
            val selected = device.confirmedNumber(field)
            val levels = remember(levelCapabilities.levels) {
                levelCapabilities.levels.sorted().map { level -> AppOption(level, "$level 档") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText(title, Modifier.weight(1f), role = AppTextRole.Summary)
                if (selected == null) AppText("当前状态未知", role = AppTextRole.Summary)
            }
            AppSegmented(levels, selected, state.canWrite(device, setting)) { level ->
                val command = if (setting == DeviceSetting.ANC_LEVEL) {
                    HeadsetCommand.AncLevel(level)
                } else {
                    HeadsetCommand.TransparencyLevel(level)
                }
                onSet(command)
            }
        }
    }
}

@Composable
private fun NoiseModeChoices(
    options: List<AppOption<String>>,
    selected: String?,
    pending: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            val columns = noiseModeColumns(options.size)
            val rows = remember(options, columns) { options.chunked(columns) }
            MiuixNoiseModeGrid(rows, columns, selected, pending, enabled, onSelect)
        }

        InterfaceStyle.MATERIAL3 -> MaterialNoiseModeGrid(
            options,
            isSelected = { it.value == selected },
            isEnabled = { enabled },
            onSelect = { onSelect(it.value) },
        ) { option ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (option.value == pending) {
                    AppBusyIndicator(24.dp, "正在设为 ${option.label}")
                } else {
                    AppIcon(noiseModeIcon(option.value), Modifier.size(24.dp))
                }
                Text(option.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

internal fun noiseModeColumns(count: Int): Int = if (count <= 4) count.coerceAtLeast(1) else 3

fun noiseModeLabel(mode: String): String = when (mode) {
    "normal" -> "标准"
    "anc" -> "降噪"
    else -> semanticLabel(mode)
}

private fun noiseModeIcon(mode: String): RoseIcon = when (mode) {
    "normal" -> RoseIcon.HEADPHONES
    "anc" -> RoseIcon.NOISE
    "transparency" -> RoseIcon.TRANSPARENCY
    "wind" -> RoseIcon.WIND
    "auto" -> RoseIcon.AUTO
    "dynamic" -> RoseIcon.EQUALIZER
    else -> RoseIcon.NOISE
}

@Composable
private fun MiuixNoiseModeGrid(
    rows: List<List<AppOption<String>>>,
    columns: Int,
    selected: String?,
    pending: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    val isSelected = option.value == selected
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val hovered by interaction.collectIsHoveredAsState()
                    val focused by interaction.collectIsFocusedAsState()
                    val contentColor = appColor(
                        when {
                            !enabled -> AppColorRole.DisabledContent
                            isSelected -> AppColorRole.SelectedContent
                            else -> AppColorRole.Summary
                        },
                    )
                    val circleColor = when {
                        isSelected -> appColor(AppColorRole.SelectedContainer)
                        enabled && (pressed || hovered) -> appColor(AppColorRole.OnSurface).copy(alpha = 0.08f)
                        else -> Color.Transparent
                    }
                    val focusRing = if (focused) Modifier.border(2.dp, appColor(AppColorRole.Primary), CircleShape) else Modifier
                    Column(
                        modifier = Modifier.weight(1f)
                            .selectable(
                                selected = isSelected,
                                interactionSource = interaction,
                                indication = null,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.value) },
                            )
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(48.dp).background(circleColor, CircleShape).then(focusRing),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (option.value == pending) {
                                AppBusyIndicator(24.dp, "正在设为 ${option.label}")
                            } else {
                                AppIcon(noiseModeIcon(option.value), Modifier.size(24.dp), tint = contentColor)
                            }
                        }
                        AppText(option.label, role = AppTextRole.Footnote, color = contentColor)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> MaterialNoiseModeGrid(
    options: List<T>,
    isSelected: (T) -> Boolean,
    isEnabled: (T) -> Boolean,
    onSelect: (T) -> Unit,
    content: @Composable (T) -> Unit,
) {
    val columns = noiseModeColumns(options.size)
    val rows = remember(options, columns) { options.chunked(columns) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                row.forEachIndexed { index, option ->
                    val chosen = isSelected(option)
                    val shapes = when {
                        row.size == 1 -> ToggleButtonDefaults.shapes(checkedShape = ButtonGroupDefaults.connectedButtonCheckedShape)
                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == row.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = chosen,
                        onCheckedChange = { checked -> if (checked) onSelect(option) },
                        modifier = Modifier.weight(1f).semantics {
                            role = Role.RadioButton
                            selected = chosen
                        },
                        enabled = isEnabled(option),
                        shapes = shapes,
                        colors = ToggleButtonDefaults.toggleButtonColors(
                            containerColor = scheme.surfaceContainerHigh,
                            contentColor = scheme.onSurfaceVariant,
                            checkedContainerColor = scheme.secondaryContainer,
                            checkedContentColor = scheme.onSecondaryContainer,
                            disabledContainerColor = if (chosen) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                            disabledContentColor = if (chosen) {
                                scheme.onSecondaryContainer.copy(alpha = 0.72f)
                            } else {
                                scheme.onSurface.copy(alpha = 0.38f)
                            },
                        ),
                    ) {
                        content(option)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

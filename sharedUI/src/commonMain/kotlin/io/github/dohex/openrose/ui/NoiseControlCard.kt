package io.github.dohex.openrose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

/** Choices always come from device capabilities; selected values always come from device replies. */
@Composable
internal fun NoiseControlCard(
    state: ControlState,
    device: DeviceState,
    showLevelControls: Boolean = true,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
    val writable = device.capabilities.writableSettings
    val pending = state.hasPendingSetting(device)
    val modesEnabled = state.canControlNoiseMode(device) && !pending && DeviceSetting.NOISE_MODE in writable
    val confirmedMode = (device.knownValue(DeviceField.NOISE_MODE) as? FieldValue.Text)?.value

    val modes = remember(capabilities.modes) {
        capabilities.modes.sortedWith(compareBy({ noiseModeOrder(it) }, { it }))
            .map { mode -> AppOption(mode, noiseModeLabel(mode)) }
    }
    AppDashboardCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("降噪模式", Modifier.weight(1f), role = AppTextRole.Title)
            val headerStatus = when {
                state.pendingSetting(device, DeviceSetting.NOISE_MODE) != null -> "等待设备确认"
                confirmedMode == null -> "当前状态未知"
                else -> null
            }
            headerStatus?.let { AppText(it, role = AppTextRole.Summary) }
        }
        NoiseModeChoices(modes, confirmedMode, modesEnabled) { mode ->
            if (mode != confirmedMode) onSet(DeviceSetting.NOISE_MODE, FieldValue.Text(mode))
        }
        if (showLevelControls) NoiseLevelControls(device, confirmedMode, state.isReady(device) && !pending, onSet)
    }
}

/** The 降噪档位 and 通透档位 selectors, each only while the confirmed mode is one it applies to. */
@Composable
private fun NoiseLevelControls(
    device: DeviceState,
    confirmedMode: String?,
    enabled: Boolean,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
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
            val selected = (device.knownValue(field) as? FieldValue.Number)?.value
            val levels = remember(levelCapabilities.levels) {
                levelCapabilities.levels.sorted().map { level -> AppOption(level, "$level 档") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText(title, Modifier.weight(1f), role = AppTextRole.Summary)
                if (selected == null) AppText("当前状态未知", role = AppTextRole.Summary)
            }
            AppSegmented(levels, selected, enabled) { level ->
                if (level != selected) onSet(setting, FieldValue.Number(level))
            }
        }
    }
}

@Composable
internal fun NoiseCycleRow(
    state: ControlState,
    device: DeviceState,
    onSet: (DeviceSetting, FieldValue) -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
    if (DeviceSetting.NOISE_CYCLE !in device.capabilities.writableSettings || capabilities.cycleModes.isEmpty()) return
    val enabled = state.isReady(device) && !state.hasPendingSetting(device)
    val flags = (device.knownValue(DeviceField.NOISE_CYCLE) as? FieldValue.NoiseCycle)?.flags
    val cycleKnown = flags != null && capabilities.cycleModes.all { flags[it] != null }
    val cycleOptions = remember(capabilities.cycleModes) {
        capabilities.cycleModes.sortedWith(compareBy({ noiseModeOrder(it) }, { it }))
            .map { mode -> AppOption(mode, noiseModeLabel(mode)) }
    }
    val checked = remember(cycleOptions, flags) {
        cycleOptions.mapNotNullTo(mutableSetOf()) { it.value.takeIf { mode -> flags?.get(mode) == true } }
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
        enabledFor = { mode -> mode !in checked || checked.size > capabilities.minimumCycleSelections },
        onToggle = { mode ->
            if (enabled && cycleKnown && (mode !in checked || checked.size > capabilities.minimumCycleSelections)) {
                val requested = capabilities.cycleModes.associateWith { option ->
                    if (option == mode) option !in checked else option in checked
                }
                onSet(DeviceSetting.NOISE_CYCLE, FieldValue.NoiseCycle(requested))
            }
        },
    )
}

@Composable
internal fun RemoteNoiseModeChoices(modes: List<String>, selected: String?, enabled: Boolean, select: (String) -> Unit) {
    NoiseModeChoices(modes.map { AppOption(it, noiseModeLabel(it)) }, selected, enabled, select)
}

@Composable
private fun NoiseModeChoices(
    options: List<AppOption<String>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val columns = if (options.size <= 4) options.size.coerceAtLeast(1) else 3
    val rows = remember(options, columns) { options.chunked(columns) }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { option ->
                            val isSelected = option.value == selected
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val hovered by interaction.collectIsHoveredAsState()
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
                                    Modifier.size(48.dp).background(circleColor, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AppIcon(noiseModeIcon(option.value), Modifier.size(24.dp), tint = contentColor)
                                }
                                AppText(option.label, role = AppTextRole.Footnote, color = contentColor)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        InterfaceStyle.MATERIAL3 -> {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEachIndexed { index, option ->
                            val isSelected = option.value == selected
                            val targetStartCorner = if (isSelected || index == 0) 16.dp else 8.dp
                            val targetEndCorner = if (isSelected || index == row.size - 1) 16.dp else 8.dp
                            val startCorner by animateDpAsState(
                                targetValue = targetStartCorner,
                                animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
                                label = "modeStartCorner",
                            )
                            val endCorner by animateDpAsState(
                                targetValue = targetEndCorner,
                                animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
                                label = "modeEndCorner",
                            )
                            val shape = RoundedCornerShape(
                                topStart = startCorner,
                                bottomStart = startCorner,
                                topEnd = endCorner,
                                bottomEnd = endCorner,
                            )
                            val containerColor by animateColorAsState(
                                targetValue = when {
                                    isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                                },
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                                label = "modeContainerColor",
                            )
                            val iconScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.05f else 1.0f,
                                animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
                                label = "modeIconScale",
                            )
                            val contentColor by animateColorAsState(
                                targetValue = when {
                                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                                label = "modeContentColor",
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(containerColor)
                                    .selectable(
                                        selected = isSelected,
                                        enabled = enabled,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(option.value) },
                                    )
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AppIcon(
                                    noiseModeIcon(option.value),
                                    modifier = Modifier
                                        .size(24.dp)
                                        .graphicsLayer(scaleX = iconScale, scaleY = iconScale),
                                    tint = contentColor,
                                )
                                AppText(option.label, role = AppTextRole.Footnote, color = contentColor)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The status notification uses the same labels in androidModule's RenderedSnapshot.kt; change both together. */
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

internal fun noiseModeOrder(mode: String): Int = when (mode) {
    "normal" -> 0
    "anc" -> 1
    "transparency" -> 2
    "wind" -> 3
    "auto" -> 4
    "dynamic" -> 5
    else -> 6
}

internal fun DeviceState.knownValue(field: DeviceField): FieldValue? = (fields[field] as? FieldReading.Known)?.value

internal fun ControlState.isReady(device: DeviceState): Boolean {
    val connection = connection as? ConnectionState.Connected ?: return false
    return connection.phase == ConnectionPhase.READY &&
        connection.deviceId == device.deviceId && connection.sessionId == device.sessionId &&
        platform.transport == TransportAvailability.Available &&
        platform.permission in setOf(PermissionState.GRANTED, PermissionState.NOT_REQUIRED)
}

internal fun ControlState.canControlNoiseMode(device: DeviceState): Boolean {
    val session = noiseModeSession ?: return false
    return session.deviceId == device.deviceId && session.sessionId == device.sessionId && platform.connectionBlockReason == null
}

internal fun DeviceSetting.displayName(): String = when (this) {
    DeviceSetting.NOISE_MODE -> "降噪模式"
    DeviceSetting.ANC_LEVEL -> "降噪档位"
    DeviceSetting.TRANSPARENCY_LEVEL -> "通透档位"
    DeviceSetting.NOISE_CYCLE -> "降噪轮换"
    DeviceSetting.EQ -> "预设 EQ"
    DeviceSetting.GAME_MODE -> "游戏模式"
    DeviceSetting.TAP_ENABLED -> "轻拍开关"
    DeviceSetting.TAP_BINDING -> "轻拍绑定"
    DeviceSetting.TOUCH_BINDING -> "触控绑定"
    DeviceSetting.TOUCH_ENABLED -> "触控总开关"
    DeviceSetting.EAR_DETECTION -> "佩戴检测功能"
    DeviceSetting.AUDIO_MODE -> "音频模式"
    DeviceSetting.MULTIPOINT -> "多点连接"
    DeviceSetting.SPATIAL_MODE -> "空间模式"
    DeviceSetting.SPATIAL_CONTENT -> "空间音频内容"
    DeviceSetting.EAR_ADAPTIVE -> "耳道自适应"
    DeviceSetting.INDEPENDENT_MIC_ACTIVATION -> "独立麦克风激活"
    DeviceSetting.INDEPENDENT_MIC_MODE -> "独立麦克风模式"
    DeviceSetting.SPATIAL_AUDIO -> "空间音频"
    DeviceSetting.PROMPT_VOLUME -> "提示音音量"
    DeviceSetting.GAIN -> "增益档位"
    DeviceSetting.HEARING_PROTECTION -> "听力保护阈值"
    DeviceSetting.BASS_BOOST -> "低音增强"
    DeviceSetting.PROMPT_LANGUAGE -> "提示音语言"
    DeviceSetting.CUSTOM_EQ -> "自定义 EQ"
}

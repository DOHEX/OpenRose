package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.settingPrerequisite
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

internal const val TRAY_EQUALIZER_ROW_TAG = "tray-eq-row"

@Composable
internal fun TrayNoiseLevelControls(
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
) {
    val capabilities = device.capabilities.noiseControl ?: return
    val confirmedMode = device.confirmedNoiseMode?.id
    val writable = device.capabilities.writableSettings
    val visible = (DeviceSetting.ANC_LEVEL in writable && capabilities.ancLevel?.isVisibleIn(confirmedMode) == true) ||
        (DeviceSetting.TRANSPARENCY_LEVEL in writable && capabilities.transparencyLevel?.isVisibleIn(confirmedMode) == true)
    if (!visible) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NoiseLevelControls(state, device, capabilities, confirmedMode, onSet)
    }
}

@Composable
internal fun TrayCustomControls(
    state: ControlState,
    device: DeviceState,
    settings: TrayPanelSettings,
    onSet: (HeadsetCommand) -> Unit,
) {
    val controls = settings.orderedEnabledControls.filter { it.isSupportedBy(device) }
    if (controls.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        controls.forEach { control ->
            when (control) {
                TrayPanelControl.GAME_MODE -> TrayToggleControl(
                    label = "游戏模式",
                    field = DeviceField.GAME_MODE,
                    setting = DeviceSetting.GAME_MODE,
                    command = HeadsetCommand::GameMode,
                    state = state,
                    device = device,
                    onSet = onSet,
                )

                TrayPanelControl.EQUALIZER -> TrayEqualizerControl(state, device, onSet)

                TrayPanelControl.SPATIAL_AUDIO -> TraySpatialAudioControl(state, device, onSet)

                TrayPanelControl.INDEPENDENT_MIC -> TrayIndependentMicControl(state, device, onSet)
            }
        }
    }
}

private fun TrayPanelControl.isSupportedBy(device: DeviceState): Boolean {
    val writable = device.capabilities.writableSettings
    return when (this) {
        TrayPanelControl.GAME_MODE -> DeviceSetting.GAME_MODE in writable
        TrayPanelControl.EQUALIZER -> device.writablePresetOptions().isNotEmpty()
        TrayPanelControl.SPATIAL_AUDIO -> DeviceSetting.SPATIAL_MODE in writable || DeviceSetting.SPATIAL_AUDIO in writable
        TrayPanelControl.INDEPENDENT_MIC -> DeviceSetting.INDEPENDENT_MIC_MODE in writable
    }
}

@Composable
private fun TrayEqualizerControl(
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
) {
    val options = device.writablePresetOptions()
    if (options.isEmpty()) return
    TrayExpandableChoiceRow(
        title = "均衡器",
        valueText = confirmedChoiceValue(DeviceField.EQ, device),
        options = options,
        selected = device.confirmedText(DeviceField.EQ),
        pending = state.pendingText(device, DeviceSetting.EQ),
        blockedReason = state.writeBlockedReason(device, DeviceSetting.EQ),
        sessionKey = device.sessionId,
        headerTag = TRAY_EQUALIZER_ROW_TAG,
        onSelect = { onSet(HeadsetCommand.EqualizerPreset(it)) },
    )
}

@Composable
private fun TraySpatialAudioControl(
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
) {
    val writable = device.capabilities.writableSettings
    if (DeviceSetting.SPATIAL_MODE in writable) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayExpandableChoiceRow(
                title = DeviceField.SPATIAL_MODE.displayName,
                valueText = confirmedChoiceValue(DeviceField.SPATIAL_MODE, device),
                options = device.capabilities.spatialModes.map { AppOption(it, semanticLabel(it)) },
                selected = device.confirmedText(DeviceField.SPATIAL_MODE),
                pending = state.pendingText(device, DeviceSetting.SPATIAL_MODE),
                blockedReason = state.writeBlockedReason(device, DeviceSetting.SPATIAL_MODE),
                sessionKey = device.sessionId,
                onSelect = { onSet(HeadsetCommand.SpatialMode(it)) },
            )
            if (DeviceSetting.SPATIAL_CONTENT in writable &&
                device.settingPrerequisite(DeviceSetting.SPATIAL_CONTENT) == null
            ) {
                TrayExpandableChoiceRow(
                    title = DeviceField.SPATIAL_CONTENT.displayName,
                    valueText = confirmedChoiceValue(DeviceField.SPATIAL_CONTENT, device),
                    options = device.capabilities.spatialContents.map { AppOption(it, semanticLabel(it)) },
                    selected = device.confirmedText(DeviceField.SPATIAL_CONTENT),
                    pending = state.pendingText(device, DeviceSetting.SPATIAL_CONTENT),
                    blockedReason = state.writeBlockedReason(device, DeviceSetting.SPATIAL_CONTENT),
                    sessionKey = device.sessionId,
                    onSelect = { onSet(HeadsetCommand.SpatialContent(it)) },
                )
            }
        }
    } else if (DeviceSetting.SPATIAL_AUDIO in writable) {
        TrayToggleControl(
            label = "空间音频",
            field = DeviceField.SPATIAL_AUDIO,
            setting = DeviceSetting.SPATIAL_AUDIO,
            command = HeadsetCommand::SpatialAudio,
            state = state,
            device = device,
            onSet = onSet,
        )
    }
}

@Composable
private fun TrayIndependentMicControl(
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
) {
    val prerequisite = device.settingPrerequisite(DeviceSetting.INDEPENDENT_MIC_MODE)
    TrayToggleControl(
        label = "独立麦克风模式",
        field = DeviceField.INDEPENDENT_MIC_MODE,
        setting = DeviceSetting.INDEPENDENT_MIC_MODE,
        command = HeadsetCommand::IndependentMicMode,
        state = state,
        device = device,
        onSet = onSet,
        prerequisite = prerequisite,
    )
}

@Composable
private fun TrayToggleControl(
    label: String,
    field: DeviceField,
    setting: DeviceSetting,
    command: (Boolean) -> HeadsetCommand,
    state: ControlState,
    device: DeviceState,
    onSet: (HeadsetCommand) -> Unit,
    prerequisite: String? = null,
) {
    val selected = device.confirmedToggle(field)
    val isKnown = selected != null
    val pending = state.pendingToggle(device, setting)
    val enabled = state.canWrite(device, setting) && prerequisite == null && isKnown
    val summary = when {
        prerequisite != null -> prerequisite
        pending == true -> "正在开启"
        pending == false -> "正在关闭"
        !isKnown -> "当前状态未知"
        else -> null
    }
    val checked = selected ?: false
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onSet(command(!checked)) }
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (!enabled) disabled()
            }
            .heightIn(min = 40.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText(
                label,
                color = if (enabled) null else appColor(AppColorRole.DisabledContent),
            )
            summary?.let { AppText(it, role = AppTextRole.Footnote) }
        }
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> MiuixSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )

            InterfaceStyle.MATERIAL3 -> Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun TrayExpandableChoiceRow(
    title: String,
    valueText: String,
    options: List<AppOption<String>>,
    selected: String?,
    pending: String?,
    blockedReason: String?,
    sessionKey: Long,
    onSelect: (String) -> Unit,
    headerTag: String? = null,
) {
    if (options.isEmpty()) return
    val enabled = blockedReason == null
    var expanded by remember(sessionKey, enabled) { mutableStateOf(false) }
    val valueColor = appColor(if (enabled) AppColorRole.Summary else AppColorRole.DisabledContent)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val headerModifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { base -> headerTag?.let { base.testTag(it) } ?: base }
            .clickable(enabled = enabled, role = Role.DropdownList) { expanded = !expanded }
            .semantics { if (!enabled) disabled() }
            .heightIn(min = 40.dp)
            .padding(vertical = 4.dp)
        Row(
            modifier = headerModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(title, color = if (enabled) null else valueColor)
            AppText(
                valueText,
                Modifier.weight(1f),
                role = AppTextRole.Summary,
                color = valueColor,
                textAlign = TextAlign.End,
            )
            ExpansionIcon(expanded, Modifier.size(18.dp), tint = valueColor)
        }
        if (expanded && enabled) {
            TrayExpandableOptions(
                options = options,
                selected = selected,
                pending = pending,
                onChoose = { value ->
                    expanded = false
                    if (value != selected) onSelect(value)
                },
            )
        }
        blockedReason?.let { AppText(it, role = AppTextRole.Footnote) }
    }
}

@Composable
private fun TrayExpandableOptions(
    options: List<AppOption<String>>,
    selected: String?,
    pending: String?,
    onChoose: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(appColor(AppColorRole.Divider).copy(alpha = 0.25f))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            TrayExpandableOptionItem(
                option = option,
                chosen = selected != null && option.value == selected,
                writing = pending != null && option.value == pending,
                onClick = { onChoose(option.value) },
            )
        }
    }
}

@Composable
private fun TrayExpandableOptionItem(
    option: AppOption<String>,
    chosen: Boolean,
    writing: Boolean,
    onClick: () -> Unit,
) {
    val itemBackground = if (chosen) {
        appColor(AppColorRole.SelectedContainer)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    val itemContent = if (chosen) {
        appColor(AppColorRole.SelectedContent)
    } else {
        appColor(AppColorRole.OnSurface)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(itemBackground)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = chosen
                option.contentDescription?.let { contentDescription = it }
            }
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(option.label, Modifier.weight(1f), color = itemContent)
        if (writing) {
            AppBusyIndicator(16.dp, "正在设为 ${option.label}")
        } else if (chosen) {
            AppIcon(RoseIcon.CHECK, Modifier.size(16.dp), tint = itemContent)
        }
    }
}

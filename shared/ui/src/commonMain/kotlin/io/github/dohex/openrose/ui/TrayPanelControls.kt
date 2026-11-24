package io.github.dohex.openrose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
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
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

internal const val TRAY_EQUALIZER_ROW_TAG = "tray-eq-row"

internal val LocalTrayPopupChange = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
    TrayDropdownChoiceRow(
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
            TrayDropdownChoiceRow(
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
                TrayDropdownChoiceRow(
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
    TrayToggleRow(
        label = label,
        checked = checked,
        enabled = enabled,
        summary = summary,
        onToggle = { onSet(command(it)) },
    )
}

@Composable
internal fun TrayToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    summary: String?,
    onToggle: (Boolean) -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> SwitchPreference(
            checked = checked,
            onCheckedChange = onToggle,
            title = label,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
            summary = summary,
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            enabled = enabled,
        )

        InterfaceStyle.MATERIAL3 -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, role = Role.Switch) { onToggle(!checked) }
                .semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                    if (!enabled) disabled()
                }
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

private data class TrayDropdownSpec(
    val title: String,
    val valueText: String,
    val options: List<AppOption<String>>,
    val selected: String?,
    val summary: String?,
    val headerTag: String?,
    val enabled: Boolean,
)

@Composable
internal fun TrayDropdownChoiceRow(
    title: String,
    valueText: String,
    options: List<AppOption<String>>,
    selected: String?,
    pending: String?,
    blockedReason: String?,
    sessionKey: Any?,
    onSelect: (String) -> Unit,
    headerTag: String? = null,
    enabled: Boolean = blockedReason == null,
) {
    if (options.isEmpty()) return
    val onPopupChange = LocalTrayPopupChange.current
    var expanded by remember(sessionKey, enabled) { mutableStateOf(false) }
    DisposableEffect(expanded, onPopupChange) {
        if (expanded) onPopupChange(true)
        onDispose {
            if (expanded) onPopupChange(false)
        }
    }
    val pendingLabel = pending?.let { requested -> options.firstOrNull { it.value == requested }?.label }
    val spec = TrayDropdownSpec(
        title = title,
        valueText = valueText,
        options = options,
        selected = selected,
        summary = blockedReason ?: pendingLabel?.let { "正在设为 $it" },
        headerTag = headerTag,
        enabled = enabled,
    )
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTrayDropdownRow(spec, { expanded = it }, onSelect)
        InterfaceStyle.MATERIAL3 -> MaterialTrayDropdownRow(spec, expanded, { expanded = it }, onSelect)
    }
}

@Composable
private fun MiuixTrayDropdownRow(
    spec: TrayDropdownSpec,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val entry = remember(spec.options, spec.selected, spec.enabled, onSelect) {
        DropdownEntry(
            spec.options.map { option ->
                DropdownItem(
                    text = option.label,
                    selected = spec.selected != null && option.value == spec.selected,
                    enabled = spec.enabled,
                    onClick = { if (option.value != spec.selected) onSelect(option.value) },
                )
            },
        )
    }
    OverlayDropdownPreference(
        entry = entry,
        title = spec.title,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { base -> spec.headerTag?.let { base.testTag(it) } ?: base },
        summary = spec.summary ?: spec.valueText.takeIf { spec.selected == null },
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        showValue = spec.selected != null,
        enabled = spec.enabled,
        onExpandedChange = onExpandedChange,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialTrayDropdownRow(
    spec: TrayDropdownSpec,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val valueColor = appColor(if (spec.enabled) AppColorRole.Summary else AppColorRole.DisabledContent)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth()) {
            val headerModifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .let { base -> spec.headerTag?.let { base.testTag(it) } ?: base }
                .clickable(enabled = spec.enabled, role = Role.DropdownList) { onExpandedChange(true) }
                .semantics { if (!spec.enabled) disabled() }
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
            Row(
                modifier = headerModifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(spec.title, color = if (spec.enabled) null else valueColor)
                AppText(
                    spec.valueText,
                    Modifier.weight(1f),
                    role = AppTextRole.Summary,
                    color = valueColor,
                    textAlign = TextAlign.End,
                )
                ExpansionIcon(expanded, Modifier.size(18.dp), tint = valueColor)
            }
            DropdownMenuPopup(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShapes(),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    spec.options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            selected = spec.selected != null && option.value == spec.selected,
                            onClick = {
                                onExpandedChange(false)
                                if (option.value != spec.selected) onSelect(option.value)
                            },
                            text = { Text(option.label) },
                            selectedLeadingIcon = { AppIcon(RoseIcon.CHECK) },
                            shapes = MenuDefaults.itemShape(index, spec.options.size),
                        )
                    }
                }
            }
        }
        spec.summary?.let { AppText(it, Modifier.padding(horizontal = 12.dp), role = AppTextRole.Footnote) }
    }
}

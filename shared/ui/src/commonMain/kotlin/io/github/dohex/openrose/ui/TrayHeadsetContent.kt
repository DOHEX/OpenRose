package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.transport.DeviceId

internal const val REMOTE_EQUALIZER_ROW_TAG = "remote-eq-row"

@Composable
internal fun AdditionalHeadsetCard(
    source: HeadsetCardSource,
    onRecoverControl: ((DeviceId) -> Unit)? = null,
    sound: DesktopSoundActions? = null,
) {
    AppDashboardCard {
        HeadsetSourceContent(source, onRecoverControl)
        sound?.let { DesktopOutputRow(it, source) }
    }
}

@Composable
internal fun HeadsetSourceContent(source: HeadsetCardSource, onRecoverControl: ((DeviceId) -> Unit)? = null) {
    val remote = source.remote
    val name = source.target?.displayName() ?: remote?.deviceName ?: return
    val profile = source.target?.profile
    HeadsetCardHeader(name, source.sourceLabel, profile?.batteryLayout ?: remote?.reading?.batteryLayout, profile?.configurationCaption)
    HeadsetTransferAction(source)
    remote?.let { RemoteHeadsetReadings(it, enabled = !source.transferBusy) }
    if (source.canRecoverControl && onRecoverControl != null) {
        val target = requireNotNull(source.target)
        AppButton(source.reconnectLabel("连接"), { onRecoverControl(target.deviceId) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun HeadsetCardHeader(title: String, source: String?, batteryLayout: BatteryLayout?, configuration: String? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(appColor(AppColorRole.Summary).copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (batteryLayout == BatteryLayout.EARBUDS_AND_CASE) {
                EarbudsArtwork(Modifier.size(40.dp))
            } else {
                AppIcon(RoseIcon.HEADPHONES, Modifier.size(24.dp), tint = appColor(AppColorRole.Summary))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText(title, Modifier.weight(1f, fill = false), role = AppTextRole.Title)
                configuration?.let { AppText(it, role = AppTextRole.Footnote, color = appColor(AppColorRole.Primary)) }
            }
            source?.let { AppText(it, role = AppTextRole.Summary) }
        }
    }
}

internal data class TrayBatteryItem(val label: String, val percent: Int?) {
    val isLow: Boolean get() = percent != null && percent <= 20
    val text: String get() = percent?.let { "$label $it%" } ?: "$label 未知"
}

@Composable
internal fun TrayBatteryPills(items: List<TrayBatteryItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            val background = if (item.isLow) {
                appColor(AppColorRole.Error).copy(alpha = 0.12f)
            } else {
                appColor(AppColorRole.OnSurface).copy(alpha = 0.05f)
            }
            val contentColor = when {
                item.isLow -> appColor(AppColorRole.Error)
                item.percent == null -> appColor(AppColorRole.Summary)
                else -> appColor(AppColorRole.OnSurface)
            }
            Box(
                Modifier
                    .background(background, RoundedCornerShape(6.dp))
                    .heightIn(min = 24.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppText(item.text, role = AppTextRole.Footnote, color = contentColor, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun TrayLocalReadings(
    summary: TraySummary,
    batteryItems: List<TrayBatteryItem>,
    pendingMode: NoiseMode?,
    selectMode: (NoiseMode) -> Unit,
) {
    if (batteryItems.isNotEmpty()) {
        TrayBatteryPills(batteryItems)
    } else {
        summary.battery?.let { AppText(it, role = AppTextRole.Summary, color = appColor(AppColorRole.OnSurface)) }
    }
    TrayNoiseModes(summary.noiseModes, selectMode)
    if (pendingMode != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppBusyIndicator(14.dp, "正在设为 ${noiseModeLabel(pendingMode.id)}")
            AppText("等待设备确认", role = AppTextRole.Summary)
        }
    } else {
        summary.noiseModeFeedback?.let { TrayModeFeedback(it, error = true) }
    }
}

@Composable
internal fun RemoteHeadsetReadings(headset: RemoteHeadset, enabled: Boolean = true, showBattery: Boolean = true) {
    val reading = headset.reading
    val baseEnabled = enabled && reading.session != null && !headset.busy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (showBattery) 10.dp else 8.dp)) {
        headset.statusLabel?.let { AppText(it, role = AppTextRole.Summary) }
        if (showBattery) {
            AppText(headset.batteryText, role = AppTextRole.Summary, color = appColor(AppColorRole.OnSurface))
        }
        RemoteNoiseModeSection(headset, baseEnabled)
        RemoteNoiseLevelsSection(headset, baseEnabled)
        RemoteEqualizerControl(headset, baseEnabled)
        RemoteGameModeControl(headset, baseEnabled)
        (headset.feedback ?: reading.error)?.let { TrayModeFeedback(it, error = !headset.busy) }
    }
}

@Composable
private fun RemoteNoiseModeSection(headset: RemoteHeadset, baseEnabled: Boolean) {
    val reading = headset.reading
    val noiseBlocked = reading.blockedReasons[DeviceSetting.NOISE_MODE]
    val choices = trayNoiseModes(reading.modes, reading.mode, baseEnabled && noiseBlocked == null)
    if (choices.isEmpty()) {
        reading.mode?.let { AppText("降噪模式 · ${noiseModeLabel(it.id)}", role = AppTextRole.Summary) }
    } else {
        TrayNoiseModes(choices) { headset.setSetting(HeadsetCommand.NoiseMode(it)) }
    }
    noiseBlocked?.let { AppText(it, role = AppTextRole.Footnote) }
}

@Composable
private fun RemoteNoiseLevelsSection(headset: RemoteHeadset, baseEnabled: Boolean) {
    val reading = headset.reading
    if (reading.ancLevels.isNotEmpty()) {
        RemoteNoiseLevelControl(
            title = "降噪档位",
            levels = reading.ancLevels,
            selected = reading.ancLevel,
            enabled = baseEnabled,
            blockedReason = reading.blockedReasons[DeviceSetting.ANC_LEVEL],
            onSelect = { headset.setSetting(HeadsetCommand.AncLevel(it)) },
        )
    }
    if (reading.transparencyLevels.isNotEmpty()) {
        RemoteNoiseLevelControl(
            title = "通透档位",
            levels = reading.transparencyLevels,
            selected = reading.transparencyLevel,
            enabled = baseEnabled,
            blockedReason = reading.blockedReasons[DeviceSetting.TRANSPARENCY_LEVEL],
            onSelect = { headset.setSetting(HeadsetCommand.TransparencyLevel(it)) },
        )
    }
}

@Composable
private fun RemoteEqualizerControl(headset: RemoteHeadset, baseEnabled: Boolean) {
    val reading = headset.reading
    if (reading.eqPresets.isEmpty()) return
    val eqBlocked = reading.blockedReasons[DeviceSetting.EQ]
    val options = remember(reading.eqPresets, reading.profile) {
        reading.eqPresets.sortedWith(presetDisplayOrder).map { AppOption(it, equalizerLabel(it, reading.profile)) }
    }
    TrayDropdownChoiceRow(
        title = "均衡器",
        valueText = reading.eqPreset?.let { equalizerLabel(it, reading.profile) } ?: "未知",
        options = options,
        selected = reading.eqPreset,
        pending = (headset.pendingCommand as? HeadsetCommand.EqualizerPreset)?.preset,
        blockedReason = eqBlocked,
        sessionKey = reading.session,
        onSelect = { headset.setSetting(HeadsetCommand.EqualizerPreset(it)) },
        headerTag = REMOTE_EQUALIZER_ROW_TAG,
        enabled = baseEnabled && eqBlocked == null,
    )
}

@Composable
private fun RemoteGameModeControl(headset: RemoteHeadset, baseEnabled: Boolean) {
    val reading = headset.reading
    if (!reading.gameModeSupported) return
    val gameBlocked = reading.blockedReasons[DeviceSetting.GAME_MODE]
    val isKnown = reading.gameMode != null
    val pendingToggle = (headset.pendingCommand as? HeadsetCommand.GameMode)?.enabled
    val summary = when {
        gameBlocked != null -> gameBlocked
        pendingToggle == true -> "正在开启"
        pendingToggle == false -> "正在关闭"
        !isKnown -> "当前状态未知"
        else -> null
    }
    TrayToggleRow(
        label = "游戏模式",
        checked = reading.gameMode ?: false,
        enabled = baseEnabled && gameBlocked == null && isKnown,
        summary = summary,
        onToggle = { headset.setSetting(HeadsetCommand.GameMode(it)) },
    )
}

@Composable
private fun RemoteNoiseLevelControl(
    title: String,
    levels: List<Int>,
    selected: Int?,
    enabled: Boolean,
    blockedReason: String?,
    onSelect: (Int) -> Unit,
) {
    val options = remember(levels) { levels.sorted().map { AppOption(it, "$it 档") } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText(title, Modifier.weight(1f), role = AppTextRole.Footnote)
            if (selected == null) AppText("当前状态未知", role = AppTextRole.Footnote)
        }
        AppSegmented(options, selected, enabled && blockedReason == null, onSelect = onSelect)
        blockedReason?.let { AppText(it, role = AppTextRole.Footnote) }
    }
}

internal fun trayNoiseModes(modes: List<NoiseMode>, confirmed: NoiseMode?, enabled: Boolean): List<TrayNoiseMode> {
    val ordered = modes.sortedBy(NoiseMode::ordinal)
    return ordered.map { mode -> TrayNoiseMode(mode, noiseModeLabel(mode.id), mode == confirmed, enabled) }
}

@Composable
private fun TrayNoiseModes(choices: List<TrayNoiseMode>, selectMode: (NoiseMode) -> Unit) {
    if (choices.isEmpty()) return
    val selected = choices.firstOrNull { it.selected }?.mode
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppText("降噪模式", role = AppTextRole.Footnote)
            if (selected == null) AppText("当前状态未知", role = AppTextRole.Footnote)
        }
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MATERIAL3 -> MaterialNoiseModeGrid(
                choices,
                isSelected = { it.selected },
                isEnabled = { it.enabled },
                onSelect = { selectMode(it.mode) },
            ) { Text(it.label) }

            InterfaceStyle.MIUIX -> choices.chunked(noiseModeColumns(choices.size)).forEach { row ->
                AppSegmented(
                    options = row.map { AppOption(it.mode, it.label) },
                    selected = selected,
                    enabled = row.all { it.enabled },
                    contentPadding = PaddingValues(4.dp),
                    onSelect = selectMode,
                )
            }
        }
    }
}

@Composable
private fun TrayModeFeedback(text: String, error: Boolean) {
    AppText(
        text,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        role = AppTextRole.Summary,
        color = appColor(if (error) AppColorRole.Error else AppColorRole.Summary),
    )
}

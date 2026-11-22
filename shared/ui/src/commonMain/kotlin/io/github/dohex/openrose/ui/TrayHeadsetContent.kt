package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.transport.DeviceId

internal fun DeviceCandidate.cardName(): String = name?.takeIf(String::isNotBlank) ?: profile?.displayName ?: deviceId.value

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
    val name = source.target?.cardName() ?: remote?.deviceName ?: return
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

@Composable
internal fun TrayLocalReadings(summary: TraySummary, pendingMode: NoiseMode?, selectMode: (NoiseMode) -> Unit) {
    summary.battery?.let { AppText(it, role = AppTextRole.Summary, color = appColor(AppColorRole.OnSurface)) }
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
internal fun RemoteHeadsetReadings(headset: RemoteHeadset, enabled: Boolean = true) {
    val reading = headset.reading
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        headset.statusLabel?.let { AppText(it, role = AppTextRole.Summary) }
        AppText(headset.batteryText, role = AppTextRole.Summary, color = appColor(AppColorRole.OnSurface))
        val choices = trayNoiseModes(reading.modes, reading.mode, enabled && reading.session != null && !headset.busy)
        if (choices.isEmpty()) {
            reading.mode?.let { AppText("降噪模式 · ${noiseModeLabel(it.id)}", role = AppTextRole.Summary) }
        } else {
            TrayNoiseModes(choices, headset.selectMode)
        }
        (headset.feedback ?: reading.error)?.let { TrayModeFeedback(it, error = !headset.busy) }
    }
}

internal fun trayNoiseModes(modes: List<NoiseMode>, confirmed: NoiseMode?, enabled: Boolean): List<TrayNoiseMode> {
    val ordered = modes.sortedBy { noiseModeOrder(it.id) }
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

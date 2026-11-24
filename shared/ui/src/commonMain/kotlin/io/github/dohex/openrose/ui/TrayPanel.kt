package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import io.github.dohex.openrose.ui.model.settingsFor
import io.github.dohex.openrose.ui.resources.Res
import io.github.dohex.openrose.ui.resources.app_icon
import io.github.dohex.openrose.ui.resources.msr_headphones
import io.github.dohex.openrose.ui.resources.msr_headset_off
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

data class TrayNoiseMode(val mode: NoiseMode, val label: String, val selected: Boolean, val enabled: Boolean)

data class TraySummary(
    val connected: Boolean,
    val title: String,
    val deviceName: String?,
    val sourceStatus: String?,
    val battery: String?,
    val compactBattery: String? = battery,
    val noiseModes: List<TrayNoiseMode>,
    val reconnectTarget: DeviceId?,
    val reconnectLabel: String,
    val addDevice: Boolean,
    val connectionHint: String?,
    val noiseModeFeedback: String? = null,
)

fun traySummary(state: ControlState, selection: PrimaryDeviceSelection): TraySummary {
    val connection = TrayConnection(state, selection.primary(state))
    val ready = connection.ready
    val desktop = state.platform.hostPlatform.isDesktop
    val battery = ready?.trayBattery(desktop)
    val compactBattery = ready?.trayCompactBattery(desktop)

    val noiseDevice = state.activeDevice()?.takeIf(state::canControlNoiseMode)
    val noiseModes = noiseDevice?.let { state.trayNoiseModes(it) }.orEmpty()

    return TraySummary(
        connected = ready != null,
        title = connection.title,
        deviceName = connection.name,
        sourceStatus = connection.sourceStatus,
        battery = battery,
        compactBattery = compactBattery,
        noiseModes = noiseModes,
        noiseModeFeedback = noiseDevice?.let { state.noiseModeFeedback(it) },
        reconnectTarget = connection.reconnectTarget,
        reconnectLabel = headsetReconnectLabel(connection.audioConnected, "重新连接"),
        addDevice = state.bondedRoseDevices().isEmpty() && state.connectionTarget == null,
        connectionHint = connection.hint,
    )
}

@Composable
fun rememberTraySummary(state: ControlState, selection: PrimaryDeviceSelection): TraySummary {
    val summary by remember(state, selection) { derivedStateOf { traySummary(state, selection) } }
    return summary
}

internal fun ControlState.writeBlockedReason(device: DeviceState, setting: DeviceSetting): String? = when {
    canWrite(device, setting) -> null
    pendingSetting(device) != null -> "正在设置，等待设备确认"
    else -> "控制会话尚未就绪，暂不能设置。"
}

internal fun ControlState.isConnectingForTray(): Boolean = when (val current = connection) {
    ConnectionState.Disconnected -> false
    is ConnectionState.Connecting -> true
    is ConnectionState.Connected -> current.phase != ConnectionPhase.READY
}

private fun ConnectionState.deviceIdOrNull(): DeviceId? = when (this) {
    ConnectionState.Disconnected -> null
    is ConnectionState.Connecting -> deviceId
    is ConnectionState.Connected -> deviceId
}

private fun ControlState.trayDeviceName(deviceId: DeviceId, profile: ModelProfile?): String {
    val candidate = connectionTarget?.takeIf { it.deviceId == deviceId }
        ?: bondedDevices.firstOrNull { it.deviceId == deviceId }
    return candidate?.name?.takeIf(String::isNotBlank)
        ?: profile?.displayName
        ?: candidate?.profile?.displayName
        ?: deviceId.value
}

object TrayIcons {
    val connected: ImageVector
        @Composable get() = vectorResource(Res.drawable.msr_headphones)
    val disconnected: ImageVector
        @Composable get() = vectorResource(Res.drawable.msr_headset_off)
}

@Composable
fun TrayPanel(
    model: OpenRoseModel,
    selection: PrimaryDeviceSelection,
    onOpenApp: (WindowDestination?) -> Unit,
    modifier: Modifier = Modifier,
    onContentHeight: (Dp) -> Unit = {},
    circulation: CirculationClient? = null,
    remoteHeadsets: List<RemoteHeadset>? = null,
    sound: DesktopSoundActions? = null,
    trayPanelSettings: TrayPanelSettings = TrayPanelSettings.DEFAULT,
    dynamicColor: Boolean = false,
    onPopupOpenChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val dark = uiSettings.appearance.isDark()
    val background = interfaceBackgroundColor(uiSettings.interfaceStyle, dark)
    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark, dynamicColor) {
        CompositionLocalProvider(LocalTrayPopupChange provides onPopupOpenChange) {
            val body: @Composable (Modifier) -> Unit = { contentModifier ->
                Box(contentModifier.fillMaxSize().background(background)) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .onSizeChanged { size ->
                                if (size.height > 0) onContentHeight(with(density) { size.height.toDp() })
                            }
                            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TrayPanelContent(model, state, selection, onOpenApp, circulation, remoteHeadsets, sound, trayPanelSettings)
                    }
                }
            }
            when (uiSettings.interfaceStyle) {
                InterfaceStyle.MIUIX -> MiuixScaffold(modifier.fillMaxSize(), containerColor = background) { padding ->
                    body(Modifier.padding(padding))
                }

                InterfaceStyle.MATERIAL3 -> body(modifier)
            }
        }
    }
}

@Composable
private fun TrayPanelContent(
    model: OpenRoseModel,
    state: ControlState,
    selection: PrimaryDeviceSelection,
    onOpenApp: (WindowDestination?) -> Unit,
    circulation: CirculationClient?,
    remoteHeadsets: List<RemoteHeadset>?,
    sound: DesktopSoundActions?,
    trayPanelSettings: TrayPanelSettings,
) {
    val summary = rememberTraySummary(state, selection)
    val cards = rememberHeadsetCards(state, selection, circulation, remoteHeadsets)
    if (cards.showLocal) {
        if (cards.local.remote != null) {
            HeadsetSourceContent(cards.local)
        } else {
            LocalTrayHeadset(model, state, summary, cards.local, sound, trayPanelSettings, onOpenApp)
        }
    }
    cards.others.forEachIndexed { index, source ->
        key(source.target?.deviceId, source.remote?.reading?.session?.peer ?: source.remote?.name) {
            if (index > 0 || cards.showLocal) TrayDivider()
            HeadsetSourceContent(source, onRecoverControl = model::connect)
            if (sound != null) DesktopOutputRow(sound, source)
        }
    }
    TrayFooter(summary.addDevice, cards.local.remote != null || cards.others.isNotEmpty(), onOpenApp)
}

@Composable
private fun TrayFooter(offerAdd: Boolean, hasRemoteHeadset: Boolean, onOpenApp: (WindowDestination?) -> Unit) {
    val addDevice = { onOpenApp(WindowDestination.ADD_DEVICE) }
    if (offerAdd && !hasRemoteHeadset) AppButton("添加耳机", addDevice)
    Column {
        TrayDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onOpenApp(null) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("打开 OpenRose", role = AppTextRole.Summary)
                AppIcon(RoseIcon.OPEN_IN_NEW, modifier = Modifier.size(14.dp), tint = appColor(AppColorRole.Summary))
            }
            if (offerAdd && hasRemoteHeadset) {
                TrayTextButton("添加耳机", addDevice)
            } else if (!offerAdd) {
                TrayTextButton("自定义") { onOpenApp(WindowDestination.TRAY_PANEL_SETTINGS) }
            }
        }
    }
}

@Composable
private fun LocalTrayHeadset(
    model: OpenRoseModel,
    state: ControlState,
    summary: TraySummary,
    source: HeadsetCardSource,
    sound: DesktopSoundActions?,
    trayPanelSettings: TrayPanelSettings,
    onOpenApp: (WindowDestination?) -> Unit,
) {
    val device = state.activeDevice()
    HeadsetCardHeader(
        title = summary.deviceName ?: summary.title,
        source = source.sourceLabel ?: summary.sourceStatus?.takeUnless { summary.deviceName == null && it == summary.title },
        batteryLayout = (device?.profile ?: source.target?.profile)?.batteryLayout,
    )
    if (device != null) {
        val onSet = model.settingsFor(device.sessionId)
        TrayLocalReadings(summary, state.pendingText(device, DeviceSetting.NOISE_MODE)?.let(NoiseMode::fromId)) { mode ->
            onSet(HeadsetCommand.NoiseMode(mode))
        }
        if (trayPanelSettings.showNoiseLevels) {
            TrayNoiseLevelControls(state, device, onSet)
        }
        TrayCustomControls(state, device, trayPanelSettings, onSet)
        SettingFeedback(
            state = state,
            device = device,
            showPending = { setting ->
                setting != DeviceSetting.NOISE_MODE &&
                    setting != DeviceSetting.GAME_MODE &&
                    setting != DeviceSetting.SPATIAL_AUDIO &&
                    setting != DeviceSetting.INDEPENDENT_MIC_MODE
            },
            includeOperation = { it != DeviceSetting.NOISE_MODE },
            onViewDetails = { onOpenApp(WindowDestination.DIAGNOSTICS) },
        )
    }
    if (sound != null) DesktopOutputRow(sound, source)
    TrayControlStatus(state, summary, source)
    PanelConnectionActions(model, state, summary, source, compact = true)
}

@Composable
private fun TrayControlStatus(state: ControlState, summary: TraySummary, source: HeadsetCardSource) {
    if (source.hasLocalAudio && !summary.connected) {
        AppText(audioWithoutControlStatus(state.isConnectingForTray()), role = AppTextRole.Summary)
    }
    if (!source.offersTransfer && !source.transferBusy && !state.isConnectingForTray()) {
        val error = state.lastError?.message
        if (error != null && !summary.connected) AppText(error, role = AppTextRole.Footnote, color = appColor(AppColorRole.Error))
        if (!source.hasLocalAudio) {
            summary.connectionHint?.takeUnless { it == error || it == summary.sourceStatus }?.let {
                AppText(it, role = AppTextRole.Footnote)
            }
        }
    }
}

@Composable
internal fun PanelConnectionActions(
    model: OpenRoseModel,
    state: ControlState,
    summary: TraySummary,
    source: HeadsetCardSource,
    compact: Boolean,
) {
    HeadsetTransferAction(source)
    if (source.transferBusy) return
    val modifier = if (compact) Modifier else Modifier.fillMaxWidth()
    if (state.isConnectingForTray()) {
        if (compact) {
            TrayTextButton("取消连接", model::disconnect)
        } else {
            AppButton("取消连接", model::disconnect, kind = AppButtonKind.Secondary, modifier = modifier)
        }
    } else if (!source.offersTransfer) {
        summary.reconnectTarget?.let { target ->
            AppButton(source.reconnectLabel("重新连接"), { model.connect(target) }, modifier = modifier)
        }
    }
}

@Composable
internal fun TrayTextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, role = AppTextRole.Summary, color = appColor(AppColorRole.OnSurface))
    }
}

@Composable
private fun TrayDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(appColor(AppColorRole.Divider)))
}

object AppLogo {
    val painter: Painter
        @Composable get() = painterResource(Res.drawable.app_icon)
}

private fun DeviceState.trayBattery(
    desktop: Boolean,
): String? = batterySummary(profile.batteryReadings(), showUnknown = desktop) { confirmedNumber(it) }

private fun DeviceState.trayCompactBattery(
    desktop: Boolean,
): String? = batterySummary(profile.batteryLayout.compactBatterySlots(), showUnknown = desktop, unknownText = "-") {
    confirmedNumber(it)
}

private fun ControlState.trayNoiseModes(device: DeviceState): List<TrayNoiseMode> {
    val enabled = canChooseNoiseMode(device)
    return trayNoiseModes(device.noiseModes, device.confirmedNoiseMode, enabled)
}

private data class TrayConnectionStatus(val label: String, val source: String? = label)

internal fun audioWithoutControlStatus(connecting: Boolean): String = if (connecting) "耳机已接入，正在恢复控制" else "耳机已接入，控制未连接"

private class TrayConnection(private val state: ControlState, private val primary: DeviceCandidate?) {
    val ready = state.activeDevice()?.takeIf { state.isReady(it) }
    private val connecting = ready == null && state.isConnectingForTray()
    private val blockReason = state.platform.connectionBlockReason
    private val showPlatformReason = state.connectionTarget == null && !state.platform.bluetoothPermissionRequired &&
        state.platform.transport is TransportAvailability.Unavailable

    private val desktop = state.platform.hostPlatform.isDesktop
    val audioConnected = primary?.matchesAudioDevice(state.audioDeviceId) == true
    val name: String?
        get() = when {
            ready != null -> state.trayDeviceName(ready.deviceId, ready.profile)

            connecting -> {
                val id = checkNotNull(state.connection.deviceIdOrNull())
                state.trayDeviceName(id, state.device?.takeIf { it.deviceId == id }?.profile)
            }

            primary != null -> state.trayDeviceName(primary.deviceId, primary.profile)

            else -> null
        }

    private val status = when {
        ready != null -> TrayConnectionStatus("已连接", if (state.deviceHasSystemAudio) "已连接" else "耳机控制已连接")
        audioConnected -> TrayConnectionStatus(audioWithoutControlStatus(connecting))
        connecting -> TrayConnectionStatus("正在连接…", state.connection.phaseOrNull()?.displayText() ?: "正在连接…")
        showPlatformReason -> TrayConnectionStatus(requireNotNull(blockReason))
        primary == null -> TrayConnectionStatus("未添加耳机", blockReason)
        else -> TrayConnectionStatus("未连接", blockReason ?: "本机未连接")
    }

    private val hasLocalConnection = connecting || ready != null || audioConnected

    val title: String
        get() = if (showPlatformReason && !hasLocalConnection) {
            status.label
        } else {
            listOfNotNull(name, status.label).joinToString(" · ")
        }

    val sourceStatus: String? get() = status.source

    val reconnectTarget: DeviceId?
        get() = if (ready == null && !connecting && blockReason == null) primary?.deviceId else null

    val hint: String?
        get() = when {
            ready != null || connecting || showPlatformReason -> null
            blockReason != null -> blockReason
            audioConnected -> state.lastError?.message
            primary != null -> primary.profile.reconnectHint(state.lastError != null, includeStatus = !desktop)
            else -> null
        }
}

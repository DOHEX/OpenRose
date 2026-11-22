package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.circulation.RemoteControlStatus
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
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

data class TrayNoiseMode(val mode: NoiseMode, val label: String, val selected: Boolean, val enabled: Boolean)

/** What the 托盘菜单 and 托盘面板 say about the headphones right now. */
data class TraySummary(
    /** A ready control session exists. */
    val connected: Boolean,
    val title: String,
    /** Battery readings in model order. Desktop trays explicitly mark missing values. */
    val battery: String?,
    /** Modes offered by the current control session, ordered and labelled like the main window. */
    val noiseModes: List<TrayNoiseMode>,
    /** The primary device to reconnect, only when neither connected nor connecting and the transport is usable. */
    val reconnectTarget: DeviceId?,
    val reconnectLabel: String,
    /** There is no bonded ROSE device and no connection target. */
    val addDevice: Boolean,
    /** Reason or guidance to display below the primary device while disconnected. */
    val connectionHint: String?,
    val noiseModeFeedback: String? = null,
)

fun traySummary(state: ControlState, selection: PrimaryDeviceSelection): TraySummary {
    val connection = TrayConnection(state, selection.primary(state))
    val ready = connection.ready
    val desktop = state.platform.hostPlatform.isDesktop
    val battery = ready?.trayBattery(desktop)

    val noiseDevice = state.activeDevice()?.takeIf(state::canControlNoiseMode)
    val noiseModes = noiseDevice?.let { state.trayNoiseModes(it) }.orEmpty()

    return TraySummary(
        connected = ready != null,
        title = connection.title,
        battery = battery,
        noiseModes = noiseModes,
        noiseModeFeedback = noiseDevice?.let { state.noiseModeFeedback(it) },
        reconnectTarget = connection.reconnectTarget,
        reconnectLabel = if (connection.audioConnected) "重试耳机控制" else "重新连接",
        addDevice = state.bondedRoseDevices().isEmpty() && state.connectionTarget == null,
        connectionHint = connection.hint,
    )
}

/**
 * [traySummary] for one composition: reused while the state and the selection it reads are unchanged, so a
 * recomposition driven by something else (a peer poll, a countdown) does not rebuild it.
 */
@Composable
fun rememberTraySummary(state: ControlState, selection: PrimaryDeviceSelection): TraySummary {
    val summary by remember(state, selection) { derivedStateOf { traySummary(state, selection) } }
    return summary
}

/** What the 快捷面板 and 托盘面板 both put above the headphones' controls. */
internal class PanelSections(
    /** Paired audio sources' headphones. Hidden while this device holds a ready session of its own. */
    val remoteHeadsets: List<RemoteHeadset>,
    /** The local status card. A source that can already be controlled replaces it, unless this device has audio or a session. */
    val statusCard: Boolean,
)

/**
 * Decides the sections once for both panels. [hostPeers] are the paired sources a host already polls for its own
 * menu; without them the sources are polled here, and only while they would show.
 */
@Composable
internal fun rememberPanelSections(
    state: ControlState,
    summary: TraySummary,
    circulation: CirculationClient?,
    hostPeers: List<RemoteHeadset>? = null,
): PanelSections {
    val holdsHeadset = state.readySession != null
    val polled = hostPeers ?: if (circulation != null && !holdsHeadset) rememberRemoteHeadsets(circulation) else emptyList()
    val peers = if (holdsHeadset) emptyList() else polled
    val remoteControllable = peers.any {
        it.reading.status == RemoteControlStatus.READY || it.reading.status == RemoteControlStatus.INITIALIZING
    }
    return PanelSections(peers, statusCard = summary.connected || !remoteControllable || state.audioDeviceId != null)
}

/** Why a setting cannot be written right now, for the control to say beside itself; null when it can be. */
internal fun ControlState.writeBlockedReason(device: DeviceState, setting: DeviceSetting): String? = when {
    canWrite(device, setting) -> null
    pendingSetting(device) != null -> "正在设置，等待设备确认"
    else -> "控制会话尚未就绪，暂不能设置。"
}

/** A connection attempt is under way, including a session that is not yet ready for control. */
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

/** Monochrome tray glyphs (Material Symbols Rounded); the tray host tints them. */
object TrayIcons {
    val connected: ImageVector
        @Composable get() = vectorResource(Res.drawable.msr_headphones)
    val disconnected: ImageVector
        @Composable get() = vectorResource(Res.drawable.msr_headset_off)
}

/**
 * Fills the popup the host sizes. [onContentHeight] reports the height that shows all content, so the host
 * can size the popup to fit; the panel still scrolls vertically if the host keeps it smaller.
 */
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
    dynamicColor: Boolean = false,
) {
    val density = LocalDensity.current
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val dark = uiSettings.appearance.isDark()
    // The popup is a plain opaque canvas: no LocalAppBackdrop, so backdrop blur stays off.
    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark, dynamicColor) {
        Column(
            modifier
                .fillMaxSize()
                .background(interfaceBackgroundColor(uiSettings.interfaceStyle, dark))
                .verticalScroll(rememberScrollState())
                .onSizeChanged { size ->
                    if (size.height > 0) onContentHeight(with(density) { size.height.toDp() })
                }
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrayPanelContent(model, state, selection, onOpenApp, circulation, remoteHeadsets, sound)
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
) {
    val summary = rememberTraySummary(state, selection)
    val sections = rememberPanelSections(state, summary, circulation, remoteHeadsets)
    val device = state.activeDevice()

    sections.remoteHeadsets.forEach { RemoteHeadsetCard(it) }
    if (sections.statusCard) TrayStatusCard(state, summary)

    if (device != null && state.canControlNoiseMode(device) && device.capabilities.noiseControl != null) {
        NoiseControlCard(state, device, showLevelControls = false, onSet = model.settingsFor(device.sessionId))
        // While the write is pending the card shows its own progress; only an outcome needs this line.
        if (summary.noiseModeFeedback != null && state.pendingSetting(device, DeviceSetting.NOISE_MODE) == null) {
            AppText(
                summary.noiseModeFeedback,
                Modifier.padding(horizontal = 24.dp),
                role = AppTextRole.Summary,
                color = appColor(AppColorRole.Error),
            )
        }
    }

    if (sound != null) DesktopOutputRow(sound, state, selection)
    circulation?.let { CirculationRow(it, state, selection, "接到这台电脑") }
    TrayActions(model, state, summary, onOpenApp)
}

/** The headphones' title, connection progress or reason it is not connected, and the battery strip when connected. */
@Composable
internal fun TrayStatusCard(state: ControlState, summary: TraySummary) {
    val device = state.activeDevice()?.takeIf { summary.connected }
    val connecting = !summary.connected && state.isConnectingForTray()
    AppDashboardCard {
        AppText(summary.title, role = AppTextRole.Title)
        when {
            connecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppBusyIndicator()
                AppText(
                    state.connection.phaseOrNull()?.displayText() ?: "正在连接…",
                    role = AppTextRole.Summary,
                    color = appColor(AppColorRole.Primary),
                )
            }

            summary.connectionHint != null -> AppText(
                summary.connectionHint,
                role = AppTextRole.Summary,
                color = if (state.lastError != null && state.platform.connectionBlockReason == null) appColor(AppColorRole.Error) else null,
            )
        }
        if (device != null) {
            BatteryStrip(device, initializing = false, showUnknown = state.platform.hostPlatform.isDesktop)
        }
    }
}

/** 取消连接, 重新连接 and 添加耳机 as the state calls for, then 打开 OpenRose. */
@Composable
internal fun TrayActions(model: OpenRoseModel, state: ControlState, summary: TraySummary, onOpenApp: (WindowDestination?) -> Unit) {
    val connecting = !summary.connected && state.isConnectingForTray()
    val inset = if (LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3) 16.dp else 12.dp
    Column(
        Modifier.fillMaxWidth().padding(horizontal = inset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (connecting) {
            AppButton("取消连接", model::disconnect, kind = AppButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }
        summary.reconnectTarget?.let { target ->
            AppButton(summary.reconnectLabel, { model.connect(target) }, kind = AppButtonKind.Primary, modifier = Modifier.fillMaxWidth())
        }
        if (summary.addDevice) {
            AppButton(
                "添加耳机",
                { onOpenApp(WindowDestination.ADD_DEVICE) },
                kind = AppButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AppButton("打开 OpenRose", { onOpenApp(null) }, kind = AppButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

/** The application icon shown by the window manager's taskbar and window switcher. */
object AppLogo {
    val painter: Painter
        @Composable get() = painterResource(Res.drawable.app_icon)
}

private fun DeviceState.trayBattery(
    desktop: Boolean,
): String? = batterySummary(profile.batteryReadings(), showUnknown = desktop) { confirmedNumber(it) }

private fun ControlState.trayNoiseModes(device: DeviceState): List<TrayNoiseMode> {
    val confirmed = device.confirmedNoiseMode
    val enabled = canWrite(device, DeviceSetting.NOISE_MODE) &&
        DeviceSetting.NOISE_MODE in device.capabilities.writableSettings
    return device.noiseModes.sortedBy { noiseModeOrder(it.id) }.map { mode ->
        TrayNoiseMode(mode, noiseModeLabel(mode.id), selected = mode == confirmed, enabled = enabled)
    }
}

/** One view of the platform and the selected headset drives the tray title, reconnect action and hint. */
private class TrayConnection(private val state: ControlState, private val primary: DeviceCandidate?) {
    val ready = state.activeDevice()?.takeIf { state.isReady(it) }
    private val connecting = ready == null && state.isConnectingForTray()
    private val blockReason = state.platform.connectionBlockReason
    private val showPlatformReason = state.connectionTarget == null && !state.platform.bluetoothPermissionRequired &&
        state.platform.transport is TransportAvailability.Unavailable

    private val desktop = state.platform.hostPlatform.isDesktop
    val audioConnected = desktop && primary != null && primary.matchesAudioDevice(state.audioDeviceId)
    val title: String
        get() = when {
            ready != null -> "${state.trayDeviceName(ready.deviceId, ready.profile)} · 已连接"

            audioConnected && connecting -> "${state.trayDeviceName(requireNotNull(primary).deviceId, primary.profile)} · 耳机已接入，正在恢复控制"

            audioConnected -> "${state.trayDeviceName(requireNotNull(primary).deviceId, primary.profile)} · 耳机已接入，控制未连接"

            connecting -> {
                val deviceId = checkNotNull(state.connection.deviceIdOrNull())
                val profile = state.device?.takeIf { it.deviceId == deviceId }?.profile
                "${state.trayDeviceName(deviceId, profile)} · 正在连接…"
            }

            showPlatformReason -> requireNotNull(blockReason)

            primary == null -> "未添加耳机"

            else -> "${state.trayDeviceName(primary.deviceId, primary.profile)} · 未连接"
        }

    val reconnectTarget: DeviceId?
        get() = if (ready == null && !connecting && blockReason == null) primary?.deviceId else null

    val hint: String?
        get() = when {
            ready != null || connecting || showPlatformReason -> null
            blockReason != null -> blockReason
            audioConnected -> state.lastError?.message
            primary != null -> primary.profile.reconnectHint(state.lastError != null)
            else -> null
        }
}

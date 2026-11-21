package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.getValue
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
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.FieldValue
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
    val ready = state.activeDevice()?.takeIf { state.isReady(it) }
    val connecting = ready == null && state.isConnectingForTray()
    val primary = selection.primary(state)
    val blockReason = state.platform.connectionBlockReason
    val showPlatformReason = state.connectionTarget == null && !state.platform.bluetoothPermissionRequired &&
        state.platform.transport is TransportAvailability.Unavailable

    val desktop = state.platform.hostPlatform.isDesktop
    val audioConnected = desktop && primary != null && primary.matchesAudioDevice(state.audioDeviceId)
    val title = when {
        ready != null -> "${state.trayDeviceName(ready.deviceId, ready.profile)} · 已连接"

        audioConnected && connecting -> "${state.trayDeviceName(primary.deviceId, primary.profile)} · 耳机已接入，正在恢复控制"

        audioConnected -> "${state.trayDeviceName(primary.deviceId, primary.profile)} · 耳机已接入，控制未连接"

        connecting -> {
            val deviceId = checkNotNull(state.connection.deviceIdOrNull())
            val profile = state.device?.takeIf { it.deviceId == deviceId }?.profile
            "${state.trayDeviceName(deviceId, profile)} · 正在连接…"
        }

        showPlatformReason -> requireNotNull(blockReason)

        primary == null -> "未添加耳机"

        else -> "${state.trayDeviceName(primary.deviceId, primary.profile)} · 未连接"
    }

    val battery = ready?.let { device ->
        device.profile.batteryReadings().mapNotNull { (label, field) ->
            (device.knownValue(field) as? FieldValue.Number)?.value?.takeIf { it in 0..100 }
                ?.let { "$label $it%" } ?: if (desktop) "$label 未知" else null
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    val noiseDevice = state.activeDevice()?.takeIf(state::canControlNoiseMode)
    val noiseModes = noiseDevice?.let { device ->
        val confirmed = device.confirmedNoiseMode
        val enabled = !state.hasPendingSetting(device) &&
            DeviceSetting.NOISE_MODE in device.capabilities.writableSettings
        device.noiseModes.sortedBy { noiseModeOrder(it.id) }.map { mode ->
            TrayNoiseMode(mode, noiseModeLabel(mode.id), selected = mode == confirmed, enabled = enabled)
        }
    }.orEmpty()

    return TraySummary(
        connected = ready != null,
        title = title,
        battery = battery,
        noiseModes = noiseModes,
        noiseModeFeedback = noiseDevice?.let { state.noiseModeFeedback(it) },
        reconnectTarget = if (ready == null && !connecting && blockReason == null) primary?.deviceId else null,
        reconnectLabel = if (audioConnected) "重试耳机控制" else "重新连接",
        addDevice = state.bondedRoseDevices().isEmpty() && state.connectionTarget == null,
        connectionHint = when {
            ready != null || connecting || showPlatformReason -> null
            blockReason != null -> blockReason
            audioConnected -> state.lastError?.message
            primary != null -> primary.profile.reconnectHint(state.lastError != null)
            else -> null
        },
    )
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
) {
    val density = LocalDensity.current
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val dark = uiSettings.appearance.isDark()
    // The popup is a plain opaque canvas: no LocalAppBackdrop, so backdrop blur stays off.
    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark) {
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

/** Whether this appearance draws dark, following the system only for [Appearance.SYSTEM]. */
@Composable
internal fun Appearance.isDark(): Boolean = when (this) {
    Appearance.SYSTEM -> isSystemInDarkTheme()
    Appearance.LIGHT -> false
    Appearance.DARK -> true
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
    val summary = traySummary(state, selection)
    val device = state.activeDevice()

    val peers = remoteHeadsets ?: circulation?.let { rememberRemoteHeadsets(it) }.orEmpty()
    peers.forEach { RemoteHeadsetCard(it) }
    val remoteControllable = peers.any { it.reading.status in setOf(RemoteControlStatus.READY, RemoteControlStatus.INITIALIZING) }
    if (summary.connected || !remoteControllable || state.audioDeviceId != null) {
        TrayStatusCard(state, summary)
    }

    if (device != null && state.canControlNoiseMode(device) && device.capabilities.noiseControl != null) {
        NoiseControlCard(state, device, showLevelControls = false, onSet = model.settingsFor(device.sessionId))
        if (summary.noiseModeFeedback != null && !state.hasPendingSetting(device)) {
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

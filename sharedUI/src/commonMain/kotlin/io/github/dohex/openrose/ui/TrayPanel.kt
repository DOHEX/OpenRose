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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.resources.Res
import io.github.dohex.openrose.ui.resources.msr_headphones
import io.github.dohex.openrose.ui.resources.msr_headset_off
import org.jetbrains.compose.resources.vectorResource

data class TrayNoiseMode(val mode: String, val label: String, val selected: Boolean, val enabled: Boolean)

/** What the 托盘菜单 and 托盘面板 say about the headphones right now. */
data class TraySummary(
    /** A ready control session exists. */
    val connected: Boolean,
    val title: String,
    /** Known battery readings only; null when there are none. */
    val battery: String?,
    /** Empty unless connected and the model has 降噪模式; ordered and labelled like the main window. */
    val noiseModes: List<TrayNoiseMode>,
    /** The primary device to reconnect, only when neither connected nor connecting and the transport is usable. */
    val reconnectTarget: DeviceId?,
    /** There is no bonded ROSE device and no connection target. */
    val addDevice: Boolean,
)

fun traySummary(state: ControlState, selection: PrimaryDeviceSelection): TraySummary {
    val ready = state.activeDevice()?.takeIf { state.isReady(it) }
    val connecting = ready == null && state.isConnectingForTray()
    val primary = selection.primary(state)
    val unavailable = state.platform.transport as? TransportAvailability.Unavailable

    val title = when {
        ready != null -> "${state.trayDeviceName(ready.deviceId, ready.profile)} · 已连接"

        connecting -> {
            val deviceId = checkNotNull(state.connection.deviceIdOrNull())
            val profile = state.device?.takeIf { it.deviceId == deviceId }?.profile
            "${state.trayDeviceName(deviceId, profile)} · 正在连接…"
        }

        state.connectionTarget == null && state.platform.transportUnavailableForHome() ->
            checkNotNull(unavailable).reason

        primary == null -> "未添加耳机"

        else -> "${state.trayDeviceName(primary.deviceId, primary.profile)} · 未连接"
    }

    val battery = ready?.let { device ->
        device.profile.batteryReadings().mapNotNull { (label, field) ->
            (device.knownValue(field) as? FieldValue.Number)?.value?.takeIf { it in 0..100 }
                ?.let { "$label $it%" }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    val noiseModes = ready?.let { device ->
        val noise = device.capabilities.noiseControl ?: return@let emptyList<TrayNoiseMode>()
        val confirmed = (device.knownValue(DeviceField.NOISE_MODE) as? FieldValue.Text)?.value
        val enabled = !state.hasPendingSetting(device) &&
            DeviceSetting.NOISE_MODE in device.capabilities.writableSettings
        noise.modes.sortedWith(compareBy({ noiseModeOrder(it) }, { it })).map { mode ->
            TrayNoiseMode(mode, noiseModeLabel(mode), selected = mode == confirmed, enabled = enabled)
        }
    }.orEmpty()

    return TraySummary(
        connected = ready != null,
        title = title,
        battery = battery,
        noiseModes = noiseModes,
        reconnectTarget = if (ready == null && !connecting && state.platform.transportUsable()) primary?.deviceId else null,
        addDevice = state.bondedRoseDevices().isEmpty() && state.connectionTarget == null,
    )
}

private fun PlatformState.transportUsable(): Boolean = permission in setOf(PermissionState.GRANTED, PermissionState.NOT_REQUIRED) &&
    transport !is TransportAvailability.Unavailable

/** A connection attempt is under way, including a session that is not yet ready for control. */
private fun ControlState.isConnectingForTray(): Boolean = when (val current = connection) {
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
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    onContentHeight: (Dp) -> Unit = {},
) {
    val density = LocalDensity.current
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val dark = when (uiSettings.appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
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
            TrayPanelContent(model, state, selection, onOpenApp)
        }
    }
}

@Composable
private fun TrayPanelContent(
    model: OpenRoseModel,
    state: ControlState,
    selection: PrimaryDeviceSelection,
    onOpenApp: () -> Unit,
) {
    val summary = traySummary(state, selection)
    val device = state.activeDevice()?.takeIf { summary.connected }
    val connecting = !summary.connected && state.isConnectingForTray()
    val permissionRequired = state.platform.permission != PermissionState.GRANTED &&
        state.platform.permission != PermissionState.NOT_REQUIRED
    val unavailableTransport = state.platform.transport as? TransportAvailability.Unavailable

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

            summary.connected || summary.addDevice -> Unit

            permissionRequired -> AppText("需要蓝牙权限才能连接这副耳机", role = AppTextRole.Summary)

            // Without a connection target the title already carries the reason.
            unavailableTransport != null ->
                if (state.connectionTarget != null) AppText(unavailableTransport.reason, role = AppTextRole.Summary)

            state.lastError != null -> AppText(
                "连接未成功 · 请确认耳机已开盖并在附近",
                role = AppTextRole.Summary,
                color = appColor(AppColorRole.Error),
            )

            else -> AppText("未连接 · 耳机开盖后将自动连接", role = AppTextRole.Summary)
        }
        if (device != null) {
            BatteryStrip(device, initializing = false)
        }
    }

    if (device != null && device.capabilities.noiseControl != null) {
        NoiseControlCard(state, device, showLevelControls = false, onSet = model::set)
    }

    val inset = if (LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3) 16.dp else 12.dp
    Column(
        Modifier.fillMaxWidth().padding(horizontal = inset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (connecting) {
            AppButton("取消连接", model::disconnect, kind = AppButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }
        summary.reconnectTarget?.let { target ->
            AppButton("重新连接", { model.connect(target) }, kind = AppButtonKind.Primary, modifier = Modifier.fillMaxWidth())
        }
        if (summary.addDevice) {
            AppButton(
                "添加耳机",
                {
                    model.showHeadphones()
                    onOpenApp()
                },
                kind = AppButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AppButton("打开 OpenRose", onOpenApp, kind = AppButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

package io.github.dohex.openrose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.ConnectionBlockReason
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.NoiseMode
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import io.github.dohex.openrose.ui.model.settingsFor
import io.github.dohex.openrose.ui.resources.Res
import io.github.dohex.openrose.ui.resources.app_icon
import io.github.dohex.openrose.ui.resources.msr_headphones
import io.github.dohex.openrose.ui.resources.msr_headset_off
import io.github.dohex.openrose.ui.resources.tray_headphones_remote
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

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

fun traySummary(state: ControlState): TraySummary {
    val connection = TrayConnection(state, state.currentHeadset)
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
        addDevice = state.bondedHeadsets.isEmpty() && state.connectionTarget == null,
        connectionHint = connection.hint,
    )
}

@Composable
fun rememberTraySummary(state: ControlState): TraySummary {
    val summary by remember(state) { derivedStateOf { traySummary(state) } }
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

object TrayIcons {
    val connected: ImageVector
        @Composable get() = vectorResource(Res.drawable.msr_headphones)
    val remote: ImageVector
        @Composable get() = vectorResource(Res.drawable.tray_headphones_remote)
    val disconnected: ImageVector
        @Composable get() = vectorResource(Res.drawable.msr_headset_off)
}

@Composable
fun TrayPanel(
    model: OpenRoseModel,
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
                            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TrayPanelContent(model, state, onOpenApp, circulation, remoteHeadsets, sound, trayPanelSettings)
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
    onOpenApp: (WindowDestination?) -> Unit,
    circulation: CirculationClient?,
    remoteHeadsets: List<RemoteHeadset>?,
    sound: DesktopSoundActions?,
    trayPanelSettings: TrayPanelSettings,
) {
    val summary = rememberTraySummary(state)
    val cards = rememberHeadsetCards(state, circulation, remoteHeadsets)
    if (cards.showLocal) {
        val localRemote = cards.local.remote
        if (localRemote != null) {
            TrayPrimaryRemoteHeadset(cards.local, localRemote)
        } else {
            LocalTrayHeadset(model, state, summary, cards.local, sound, trayPanelSettings, onOpenApp)
        }
    }
    cards.others.forEachIndexed { index, source ->
        val itemKey = source.target?.deviceId ?: source.remote?.reading?.session?.peer ?: source.remote?.name ?: index
        key(itemKey) {
            if (index > 0 || cards.showLocal) TrayDivider()
            val remote = source.remote
            if (!cards.showLocal && index == 0 && remote != null) {
                TrayPrimaryRemoteHeadset(source, remote)
            } else {
                TraySecondaryHeadset(
                    source = source,
                    itemKey = itemKey,
                    onRecoverControl = model::connect,
                    sound = sound,
                )
            }
        }
    }
    TrayFooter(summary.addDevice, cards.local.remote != null || cards.others.isNotEmpty(), onOpenApp)
}

@Composable
private fun TrayFooter(offerAdd: Boolean, hasRemoteHeadset: Boolean, onOpenApp: (WindowDestination?) -> Unit) {
    val addDevice = { onOpenApp(WindowDestination.ADD_DEVICE) }
    if (offerAdd && !hasRemoteHeadset) TrayCompactPrimaryButton("添加耳机", addDevice)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrayDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrayContentTextButton(
                onClick = { onOpenApp(null) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("打开 OpenRose", role = AppTextRole.Footnote)
                    AppIcon(RoseIcon.OPEN_IN_NEW, modifier = Modifier.size(13.dp), tint = appColor(AppColorRole.Summary))
                }
            }
            if (offerAdd && hasRemoteHeadset) {
                TrayTextButton("添加耳机", addDevice)
            } else if (!offerAdd) {
                TrayTextButton("自定义", { onOpenApp(WindowDestination.TRAY_PANEL_SETTINGS) })
            }
        }
    }
}

@Composable
private fun TrayPrimaryRemoteHeadset(source: HeadsetCardSource, remote: RemoteHeadset) {
    val name = source.target?.displayName() ?: remote.deviceName ?: return
    val profile = source.target?.profile
    TrayHeadsetHeader(
        title = name,
        subtitle = "音源 · ${remote.name}",
        batteryLayout = profile?.batteryLayout ?: remote.reading.batteryLayout,
        configuration = profile?.configurationCaption,
        action = {
            if (source.offersTransfer) {
                TrayInlineActionButton(
                    text = source.trayTransferLabel,
                    onClick = source::pull,
                    enabled = source.transferEnabled,
                    tooltip = source.transferHint,
                )
            }
        },
    )
    source.transferFeedback?.let { AppText(it, role = AppTextRole.Footnote) }
    TrayBatteryPills(remote.trayBatteryItems())
    RemoteHeadsetReadings(remote, enabled = !source.transferBusy, showBattery = false)
}

@Composable
private fun TraySecondaryHeadset(
    source: HeadsetCardSource,
    itemKey: Any,
    onRecoverControl: (DeviceId) -> Unit,
    sound: DesktopSoundActions?,
) {
    val remote = source.remote
    val name = source.target?.displayName() ?: remote?.deviceName ?: return
    val profile = source.target?.profile
    var expanded by remember(itemKey) { mutableStateOf(false) }
    val subtitle = if (remote != null) {
        listOfNotNull("音源 · ${remote.name}", remote.compactBatteryOrNull).joinToString(" · ")
    } else {
        source.sourceLabel
    }
    TrayHeadsetHeader(
        title = name,
        subtitle = subtitle,
        batteryLayout = profile?.batteryLayout ?: remote?.reading?.batteryLayout,
        configuration = profile?.configurationCaption,
        compact = true,
        action = {
            when {
                source.offersTransfer -> TrayInlineActionButton(
                    text = source.trayTransferLabel,
                    onClick = source::pull,
                    enabled = source.transferEnabled,
                    tooltip = source.transferHint,
                )

                source.canRecoverControl -> {
                    val target = requireNotNull(source.target)
                    TrayInlineActionButton(
                        text = source.reconnectLabel("连接"),
                        onClick = { onRecoverControl(target.deviceId) },
                    )
                }
            }
        },
    )
    source.transferFeedback?.let { AppText(it, role = AppTextRole.Footnote) }
    if (remote != null) {
        TrayContentTextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(if (expanded) "收起远端控制" else "展开远端控制", role = AppTextRole.Footnote)
                ExpansionIcon(expanded, Modifier.size(14.dp), tint = appColor(AppColorRole.Summary))
            }
        }
        if (expanded) {
            TrayBatteryPills(remote.trayBatteryItems())
            RemoteHeadsetReadings(remote, enabled = !source.transferBusy, showBattery = false)
        }
    }
    if (sound != null) DesktopOutputRow(sound, source, compact = true)
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
    val desktop = state.platform.hostPlatform.isDesktop
    val subtitle = source.sourceLabel ?: summary.sourceStatus?.takeUnless { summary.deviceName == null && it == summary.title }
    TrayHeadsetHeader(
        title = summary.deviceName ?: summary.title,
        subtitle = subtitle,
        batteryLayout = (device?.profile ?: source.target?.profile)?.batteryLayout,
        connectedDot = summary.connected,
        action = { LocalTrayHeaderAction(model, state, summary, source) },
    )
    source.transferFeedback?.let { AppText(it, role = AppTextRole.Footnote) }
    if (device != null) {
        val onSet = model.settingsFor(device.sessionId)
        val batteryItems = trayBatteryItems(device.profile.batteryLayout.trayBatterySlots(), showUnknown = desktop) {
            device.confirmedNumber(it)
        }
        TrayLocalReadings(
            summary = summary,
            batteryItems = batteryItems,
            pendingMode = state.pendingText(device, DeviceSetting.NOISE_MODE)?.let(NoiseMode::fromId),
        ) { mode ->
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
    if (sound != null) DesktopOutputRow(sound, source, compact = true)
    TrayControlStatus(state, summary, source)
}

@Composable
private fun LocalTrayHeaderAction(
    model: OpenRoseModel,
    state: ControlState,
    summary: TraySummary,
    source: HeadsetCardSource,
) {
    when {
        source.offersTransfer -> TrayInlineActionButton(
            text = source.trayTransferLabel,
            onClick = source::pull,
            enabled = source.transferEnabled,
            tooltip = source.transferHint,
        )

        !source.transferBusy && state.isConnectingForTray() -> TrayInlineActionButton(
            text = "取消连接",
            onClick = model::disconnect,
            emphasized = false,
        )

        !source.transferBusy && !source.offersTransfer -> summary.reconnectTarget?.let { target ->
            TrayInlineActionButton(
                text = source.reconnectLabel("重新连接"),
                onClick = { model.connect(target) },
            )
        }
    }
}

@Composable
private fun TrayHeadsetHeader(
    title: String,
    subtitle: String?,
    batteryLayout: BatteryLayout?,
    configuration: String? = null,
    connectedDot: Boolean = false,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val iconContainerSize = if (compact) 34.dp else 38.dp
    val iconRadius = if (compact) 9.dp else 10.dp
    val artworkSize = if (compact) 30.dp else 34.dp
    val fallbackIconSize = if (compact) 18.dp else 20.dp
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(iconContainerSize).background(appColor(AppColorRole.Summary).copy(alpha = 0.08f), RoundedCornerShape(iconRadius)),
            contentAlignment = Alignment.Center,
        ) {
            if (batteryLayout == BatteryLayout.EARBUDS_AND_CASE) {
                EarbudsArtwork(Modifier.size(artworkSize))
            } else {
                AppIcon(RoseIcon.HEADPHONES, Modifier.size(fallbackIconSize), tint = appColor(AppColorRole.Summary))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppText(
                    title,
                    Modifier.weight(1f, fill = false),
                    role = if (compact) AppTextRole.Body else AppTextRole.Title,
                    maxLines = 1,
                )
                configuration?.let { AppText(it, role = AppTextRole.Footnote, color = appColor(AppColorRole.Primary), maxLines = 1) }
            }
            subtitle?.let { text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (connectedDot) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF34C759)))
                    }
                    AppText(text, role = AppTextRole.Footnote, maxLines = 1)
                }
            }
        }
        action?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrayInlineActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = true,
    tooltip: String? = null,
) {
    val button: @Composable () -> Unit = {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> MiuixTextButton(
                text = text,
                onClick = onClick,
                enabled = enabled,
                cornerRadius = 8.dp,
                minWidth = 0.dp,
                minHeight = 28.dp,
                colors = if (emphasized) MiuixButtonDefaults.textButtonColorsPrimary() else MiuixButtonDefaults.textButtonColors(),
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                textStyle = MiuixTheme.textStyles.footnote1,
            )

            InterfaceStyle.MATERIAL3 -> if (emphasized) {
                FilledTonalButton(
                    onClick = onClick,
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(text) }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(text) }
            }
        }
    }
    if (tooltip != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { AppText(tooltip, role = AppTextRole.Footnote) } },
            state = rememberTooltipState(),
        ) {
            button()
        }
    } else {
        button()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrayCompactPrimaryButton(text: String, onClick: () -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTextButton(
            text = text,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 9.dp,
            minHeight = 34.dp,
            colors = MiuixButtonDefaults.textButtonColorsPrimary(),
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            textStyle = MiuixTheme.textStyles.body2,
        )

        InterfaceStyle.MATERIAL3 -> FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(text) }
    }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TrayTextButton(text: String, onClick: () -> Unit, emphasized: Boolean = false) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTextButton(
            text = text,
            onClick = onClick,
            cornerRadius = 8.dp,
            minWidth = 0.dp,
            minHeight = 32.dp,
            colors = MiuixButtonDefaults.textButtonColors(
                color = Color.Transparent,
                disabledColor = Color.Transparent,
                textColor = if (emphasized) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                disabledTextColor = MiuixTheme.colorScheme.disabledOnSurface,
            ),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            textStyle = MiuixTheme.textStyles.footnote1,
        )

        InterfaceStyle.MATERIAL3 -> TextButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) { Text(text) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrayContentTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    content: @Composable () -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixButton(
            onClick = onClick,
            modifier = modifier,
            cornerRadius = 8.dp,
            minWidth = 0.dp,
            minHeight = 28.dp,
            colors = MiuixButtonDefaults.buttonColors(
                color = Color.Transparent,
                disabledColor = Color.Transparent,
                contentColor = MiuixTheme.colorScheme.onSurface,
                disabledContentColor = MiuixTheme.colorScheme.disabledOnSurface,
            ),
            insideMargin = contentPadding,
            content = { content() },
        )

        InterfaceStyle.MATERIAL3 -> TextButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
            modifier = modifier,
            contentPadding = contentPadding,
        ) { content() }
    }
}

@Composable
private fun TrayDivider() {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixHorizontalDivider()
        InterfaceStyle.MATERIAL3 -> HorizontalDivider()
    }
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
    private val blockReason = state.connectionBlockReason
    private val blockReasonText = blockReason?.displayText(state.platform.hostPlatform)
    private val showPlatformReason = state.connectionTarget == null && blockReason is ConnectionBlockReason.TransportUnavailable
    private val desktop = state.platform.hostPlatform.isDesktop
    val audioConnected = primary?.matchesAudioDevice(state.audioDeviceId) == true
    val name: String?
        get() = when {
            ready != null -> state.deviceDisplayName(ready)

            connecting -> {
                val id = checkNotNull(state.connection.deviceIdOrNull())
                val current = state.device?.takeIf { it.deviceId == id }
                state.deviceDisplayName(id, current?.profile, current?.classicAddress)
            }

            primary != null -> state.deviceDisplayName(primary.deviceId, primary.profile, primary.classicAddress)

            else -> null
        }

    private val status = when {
        ready != null -> TrayConnectionStatus("已连接", if (state.deviceHasSystemAudio) "已连接" else "耳机控制已连接")
        audioConnected -> TrayConnectionStatus(audioWithoutControlStatus(connecting))
        connecting -> TrayConnectionStatus("正在连接…", state.connection.phaseOrNull()?.displayText() ?: "正在连接…")
        showPlatformReason -> TrayConnectionStatus(requireNotNull(blockReasonText))
        primary == null -> TrayConnectionStatus("未添加耳机", blockReasonText)
        else -> TrayConnectionStatus("未连接", blockReasonText ?: "本机未连接")
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
            blockReasonText != null -> blockReasonText
            audioConnected -> state.lastError?.message
            primary != null -> primary.reconnectGuidance.reconnectHint(state.lastError != null, includeStatus = !desktop)
            else -> null
        }
}

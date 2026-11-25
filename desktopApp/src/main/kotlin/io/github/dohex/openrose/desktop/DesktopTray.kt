package io.github.dohex.openrose.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.composenativetray.menu.api.ComposableTrayMenuScope
import dev.nucleusframework.composenativetray.menu.api.TrayMenuBuilder
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.nucleusframework.composenativetray.trayapp.TrayApp
import dev.nucleusframework.composenativetray.trayapp.rememberTrayAppState
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.ui.DesktopSoundActions
import io.github.dohex.openrose.ui.LocalCirculationVisible
import io.github.dohex.openrose.ui.RemoteHeadset
import io.github.dohex.openrose.ui.TrayIcons
import io.github.dohex.openrose.ui.TrayPanel
import io.github.dohex.openrose.ui.TrayPanelSettings
import io.github.dohex.openrose.ui.TraySummary
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import io.github.dohex.openrose.ui.noiseModeLabel
import io.github.dohex.openrose.ui.rememberRemoteHeadsets
import io.github.dohex.openrose.ui.rememberTraySummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val screen: DpSize? by lazy { usableScreenSize() }

private val trayPanelSize: DpSize by lazy { DpSize(340.dp, 520.dp).fitTo(screen) }

private fun trayPanelSizeFor(contentHeight: Dp): DpSize = DpSize(trayPanelSize.width, contentHeight).fitTo(screen)

internal const val TRAY_PANEL_WINDOW_TITLE = "OpenRose 托盘面板"

private const val FOCUS_LOSS_GRACE_MS = 1_000L

private const val POINTER_EXIT_GRACE_MS = 500L

internal enum class TrayPanelHost {
    ANCHORED_POPUP,

    WINDOW,
}

internal fun trayPanelHost(): TrayPanelHost = if (
    Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.Unknown
) {
    TrayPanelHost.WINDOW
} else {
    TrayPanelHost.ANCHORED_POPUP
}

internal enum class TrayIconState {
    CONNECTED,
    REMOTE,
    DISCONNECTED,
}

internal fun trayIconState(summary: TraySummary, remoteSource: RemoteHeadset?): TrayIconState = when {
    summary.connected -> TrayIconState.CONNECTED
    remoteSource != null -> TrayIconState.REMOTE
    else -> TrayIconState.DISCONNECTED
}

internal fun trayTooltip(started: Boolean, summary: TraySummary, remoteSource: RemoteHeadset?): String = when {
    !started -> "OpenRose"

    !summary.connected && remoteSource != null -> listOfNotNull(
        "${remoteSource.deviceName} · ${remoteSource.name}",
        remoteSource.compactBatteryOrNull ?: remoteSource.statusLabel,
    ).joinToString("\n")

    else -> listOfNotNull(summary.title, summary.compactBattery).joinToString("\n")
}

internal fun trayIconTint(
    platform: Platform,
    desktopEnvironment: LinuxDesktopEnvironment,
    systemDark: Boolean,
): Color? = if (platform == Platform.Linux && desktopEnvironment == LinuxDesktopEnvironment.Unknown) {
    if (systemDark) Color.White else Color.Black
} else {
    null
}

@Composable
internal fun NucleusApplicationScope.DesktopTray(
    host: TrayPanelHost,
    model: OpenRoseModel,
    uiScope: CoroutineScope,
    circulation: CirculationClient,
    sound: DesktopSoundActions?,
    trayPanelSettings: TrayPanelSettings,
    onOpenApp: () -> Unit,
    onExit: () -> Unit,
    onStarted: () -> Unit,
) {
    val state by model.state.collectAsState()
    val summary = rememberTraySummary(state)
    val sessionId = state.noiseModeSession?.sessionId
    val trayState = rememberTrayAppState(initialWindowSize = trayPanelSize)
    val anchoredOpen by trayState.isVisible.collectAsState()
    var panelOpen by remember { mutableStateOf(false) }
    var panelHeight by remember { mutableStateOf<Dp?>(null) }
    var menuRefresh by remember { mutableIntStateOf(0) }
    val peers = rememberRemoteHeadsets(circulation, if (panelOpen || anchoredOpen) 2_000L else 30_000L, menuRefresh)
    val remoteSource = peers.firstOrNull { it.deviceName != null }
    val icon = when (trayIconState(summary, remoteSource)) {
        TrayIconState.CONNECTED -> TrayIcons.connected
        TrayIconState.REMOTE -> TrayIcons.remote
        TrayIconState.DISCONNECTED -> TrayIcons.disconnected
    }
    val iconTint = trayIconTint(Platform.Current, LinuxDesktopEnvironment.Current, isSystemInDarkTheme())
    var started by remember { mutableStateOf(false) }
    val tooltip = trayTooltip(started, summary, remoteSource)

    val actions = remember(model, uiScope, onOpenApp, onExit) { TrayActions(model, uiScope, onOpenApp, onExit) }
    fun entries(showPanel: () -> Unit) = remoteMenuEntries(peers, uiScope) +
        actions.localMenu(summary, sessionId, remoteSource != null, showPanel)

    when (host) {
        TrayPanelHost.ANCHORED_POPUP -> {
            val menu = entries { trayState.show() }
            TrayApp(
                icon = icon,
                tint = iconTint,
                tooltip = tooltip,
                state = trayState,
                windowSize = trayPanelSize,
                windowsTitle = "OpenRose",
                menu = { render(menu) },
            ) {
                CompositionLocalProvider(LocalCirculationVisible provides anchoredOpen) {
                    TrayPanel(
                        model,
                        onOpenApp = {
                            trayState.hide()
                            actions.open(it)
                        },
                        circulation = circulation,
                        sound = sound,
                        trayPanelSettings = trayPanelSettings,
                        remoteHeadsets = peers,
                        onContentHeight = { trayState.setWindowSize(trayPanelSizeFor(it)) },
                    )
                }
            }
        }

        TrayPanelHost.WINDOW -> {
            val menu = entries { panelOpen = true }
            Tray(
                icon = icon,
                tint = iconTint,
                tooltip = tooltip,
                onMenuOpened = { uiScope.launch { menuRefresh++ } },
                primaryAction = {
                    uiScope.launch { panelOpen = !panelOpen }
                },
            ) { render(menu) }
            if (panelOpen) {
                TrayPanelWindow(
                    model = model,
                    circulation = circulation,
                    sound = sound,
                    trayPanelSettings = trayPanelSettings,
                    peers = peers,
                    initialSize = panelHeight?.let(::trayPanelSizeFor) ?: trayPanelSize,
                    onContentHeight = { panelHeight = it },
                    onDismiss = { panelOpen = false },
                    onOpenApp = {
                        panelOpen = false
                        actions.open(it)
                    },
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        started = true
        onStarted()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NucleusApplicationScope.TrayPanelWindow(
    model: OpenRoseModel,
    circulation: CirculationClient,
    sound: DesktopSoundActions?,
    trayPanelSettings: TrayPanelSettings,
    peers: List<RemoteHeadset>,
    initialSize: DpSize,
    onContentHeight: (Dp) -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: (WindowDestination?) -> Unit,
) {
    val windowState = rememberWindowState(size = initialSize)
    DecoratedWindow(
        onCloseRequest = onDismiss,
        state = windowState,
        title = TRAY_PANEL_WINDOW_TITLE,
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        onPreviewKeyEvent = { event ->
            (event.type == KeyEventType.KeyUp && event.key == Key.Escape).also { if (it) onDismiss() }
        },
    ) {
        val window = nucleusWindow
        var pointerInside by remember { mutableStateOf<Boolean?>(null) }
        var openPopups by remember { mutableIntStateOf(0) }
        LaunchedEffect(window, openPopups) {
            if (openPopups > 0) return@LaunchedEffect
            window.requestFocus()
            val gainedFocus = withTimeoutOrNull(FOCUS_LOSS_GRACE_MS) { window.focusFlow.first { it } } != null
            window.focusFlow.collectLatest { focused ->
                if (!focused) {
                    delay(FOCUS_LOSS_GRACE_MS)
                    if (gainedFocus || pointerInside != true) onDismiss()
                }
            }
        }
        LaunchedEffect(pointerInside, openPopups) {
            if (openPopups == 0 && pointerInside == false) {
                delay(POINTER_EXIT_GRACE_MS)
                onDismiss()
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Enter) { pointerInside = true }
                .onPointerEvent(PointerEventType.Exit) { if (pointerInside != null) pointerInside = false },
        ) {
            TrayPanel(
                model,
                onOpenApp,
                circulation = circulation,
                sound = sound,
                trayPanelSettings = trayPanelSettings,
                remoteHeadsets = peers,
                onContentHeight = { height ->
                    onContentHeight(height)
                    val target = trayPanelSizeFor(height)
                    if (windowState.size != target) windowState.size = target
                },
                onPopupOpenChange = { open ->
                    openPopups = (openPopups + if (open) 1 else -1).coerceAtLeast(0)
                },
            )
        }
    }
}

private sealed interface TrayMenuEntry {
    data class Label(val text: String) : TrayMenuEntry
    data object Separator : TrayMenuEntry
    data class NoiseMode(
        val label: String,
        val selected: Boolean,
        val enabled: Boolean,
        val onSelect: () -> Unit,
    ) : TrayMenuEntry
    data class Action(val label: String, val onClick: () -> Unit) : TrayMenuEntry
}

private fun remoteMenuEntries(peers: List<RemoteHeadset>, uiScope: CoroutineScope): List<TrayMenuEntry> = buildList {
    peers.forEach { peer ->
        val reading = peer.reading
        val device = peer.deviceName
        if (device != null) {
            add(TrayMenuEntry.Label("$device · ${peer.sourceLabel}"))
            peer.statusLabel?.let { add(TrayMenuEntry.Label(it)) }
            add(TrayMenuEntry.Label(peer.compactBatteryText))
            val modeEnabled = !peer.busy && reading.blockedReasons[DeviceSetting.NOISE_MODE] == null
            reading.modes.forEach { mode ->
                val entry = TrayMenuEntry.NoiseMode(noiseModeLabel(mode.id), mode == reading.mode, modeEnabled) {
                    uiScope.launch { peer.setSetting(HeadsetCommand.NoiseMode(mode)) }
                }
                add(entry)
            }
        } else {
            add(TrayMenuEntry.Label(peer.offlineLabel))
        }
        peer.feedback?.let { add(TrayMenuEntry.Label(it)) }
        add(TrayMenuEntry.Separator)
    }
}

private class TrayActions(
    private val model: OpenRoseModel,
    private val uiScope: CoroutineScope,
    private val showApp: () -> Unit,
    private val exit: () -> Unit,
) {
    fun open(destination: WindowDestination?) {
        destination?.let(model.navigation::open)
        showApp()
    }

    fun localMenu(
        summary: TraySummary,
        sessionId: Long?,
        hasRemoteSource: Boolean,
        showPanel: () -> Unit,
    ): List<TrayMenuEntry> = buildList {
        fun action(label: String, perform: () -> Unit) = add(TrayMenuEntry.Action(label) { uiScope.launch { perform() } })
        add(TrayMenuEntry.Label(if (hasRemoteSource) "本机 · ${summary.title}" else summary.title))
        summary.compactBattery?.let { add(TrayMenuEntry.Label(it)) }
        if (summary.noiseModes.isNotEmpty()) {
            add(TrayMenuEntry.Separator)
            summary.noiseModeFeedback?.let { add(TrayMenuEntry.Label(it)) }
            summary.noiseModes.forEach { choice ->
                add(
                    TrayMenuEntry.NoiseMode(choice.label, choice.selected, choice.enabled) {
                        if (sessionId != null) uiScope.launch { model.set(sessionId, HeadsetCommand.NoiseMode(choice.mode)) }
                    },
                )
            }
        }
        summary.reconnectTarget?.let { device -> action(summary.reconnectLabel) { model.connect(device) } }
        if (summary.addDevice) action("添加耳机") { open(WindowDestination.ADD_DEVICE) }
        add(TrayMenuEntry.Separator)
        action("托盘面板", showPanel)
        action("打开 OpenRose", showApp)
        action("退出", exit)
    }
}

private fun TrayMenuBuilder.render(entries: List<TrayMenuEntry>) = entries.forEach { entry ->
    when (entry) {
        is TrayMenuEntry.Label -> Item(entry.text, isEnabled = false)

        TrayMenuEntry.Separator -> Divider()

        is TrayMenuEntry.NoiseMode -> CheckableItem(
            label = entry.label,
            checked = entry.selected,
            onCheckedChange = { if (!entry.selected) entry.onSelect() },
            isEnabled = entry.enabled,
        )

        is TrayMenuEntry.Action -> Item(entry.label, onClick = entry.onClick)
    }
}

@Composable
private fun ComposableTrayMenuScope.render(entries: List<TrayMenuEntry>) = entries.forEach { entry ->
    when (entry) {
        is TrayMenuEntry.Label -> Item(entry.text, isEnabled = false)

        TrayMenuEntry.Separator -> Divider()

        is TrayMenuEntry.NoiseMode -> CheckableItem(
            label = entry.label,
            checked = entry.selected,
            onCheckedChange = { if (!entry.selected) entry.onSelect() },
            isEnabled = entry.enabled,
        )

        is TrayMenuEntry.Action -> Item(entry.label, onClick = entry.onClick)
    }
}

package io.github.dohex.openrose.desktop

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.ui.AppLogo
import io.github.dohex.openrose.ui.DesktopSoundActions
import io.github.dohex.openrose.ui.LocalCirculationVisible
import io.github.dohex.openrose.ui.PrimaryDeviceSelection
import io.github.dohex.openrose.ui.RemoteHeadset
import io.github.dohex.openrose.ui.TrayIcons
import io.github.dohex.openrose.ui.TrayPanel
import io.github.dohex.openrose.ui.TraySummary
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import io.github.dohex.openrose.ui.noiseModeLabel
import io.github.dohex.openrose.ui.rememberRemoteHeadsets
import io.github.dohex.openrose.ui.remoteBatteryText
import io.github.dohex.openrose.ui.traySummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.dohex.openrose.core.NoiseMode as HeadsetNoiseMode

/** The panel's width, and its height until the content is measured; the host then fits the height to the content. */
private val trayPanelSize = DpSize(360.dp, 440.dp)

/** Window rules (for example niri's `match title=`) can float the panel window by this exact title. */
internal const val TRAY_PANEL_WINDOW_TITLE = "OpenRose 托盘面板"

/** How long focus may stay elsewhere before the panel window counts as dismissed. */
private const val FOCUS_LOSS_GRACE_MS = 1_000L

/** How long the pointer may be outside the panel window, after reaching it, before the panel closes. */
private const val POINTER_EXIT_GRACE_MS = 500L

/** How the 托盘面板 reaches the screen. */
internal enum class TrayPanelHost {
    /** TrayApp's popup, placed beside the tray by desktop-environment convention. */
    ANCHORED_POPUP,

    /** An ordinary window that the compositor places. */
    WINDOW,
}

/**
 * Unrecognised Linux desktops are mostly tiling compositors (niri, Hyprland, Sway). On niri with
 * xwayland-satellite, TrayApp's override-redirect X11 popup is never presented and wedges the UI loop,
 * so those desktops get a compositor-placed Wayland window instead.
 */
internal fun trayPanelHost(): TrayPanelHost = if (
    Platform.Current == Platform.Linux && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.Unknown
) {
    TrayPanelHost.WINDOW
} else {
    TrayPanelHost.ANCHORED_POPUP
}

/** The tray icon, its 托盘菜单 and 托盘面板; every action goes through the shared model. */
@Composable
internal fun NucleusApplicationScope.DesktopTray(
    host: TrayPanelHost,
    model: OpenRoseModel,
    selection: PrimaryDeviceSelection,
    uiScope: CoroutineScope,
    circulation: CirculationClient,
    sound: DesktopSoundActions?,
    onOpenApp: () -> Unit,
    onExit: () -> Unit,
    onStarted: () -> Unit,
) {
    val state by model.state.collectAsState()
    val summary = traySummary(state, selection)
    val sessionId = state.noiseModeSession?.sessionId
    val trayState = rememberTrayAppState(initialWindowSize = trayPanelSize)
    val anchoredOpen by trayState.isVisible.collectAsState()
    var panelOpen by remember { mutableStateOf(false) }
    var menuRefresh by remember { mutableIntStateOf(0) }
    val peers = rememberRemoteHeadsets(circulation, if (panelOpen || anchoredOpen) 2_000L else 30_000L, menuRefresh)
    val remoteSource = peers.firstOrNull { it.deviceName != null }
    val icon = if (summary.connected || remoteSource != null) TrayIcons.connected else TrayIcons.disconnected
    // The Linux tray fixes the item's Title from the first tooltip it sees, so that one names the app.
    var started by remember { mutableStateOf(false) }
    val tooltip = when {
        !started -> "OpenRose"
        !summary.connected && remoteSource != null -> "${remoteSource.deviceName} · ${remoteSource.name}"
        else -> listOfNotNull(summary.title, summary.battery).joinToString(" · ")
    }

    fun openApp(destination: WindowDestination?) {
        destination?.let(model.navigation::open)
        onOpenApp()
    }

    // Native menu callbacks arrive off the UI thread; every action runs on it.
    fun entries(showPanel: () -> Unit) = remoteMenuEntries(peers, uiScope) + trayMenuEntries(summary).map { entry ->
        when (entry) {
            is TrayMenuEntry.Action -> entry.copy(onClick = {
                uiScope.launch {
                    when (entry.target) {
                        TrayMenuTarget.RECONNECT -> summary.reconnectTarget?.let(model::connect)
                        TrayMenuTarget.ADD_DEVICE -> openApp(WindowDestination.ADD_DEVICE)
                        TrayMenuTarget.PANEL -> showPanel()
                        TrayMenuTarget.OPEN_APP -> onOpenApp()
                        TrayMenuTarget.EXIT -> onExit()
                    }
                }
            })

            is TrayMenuEntry.NoiseMode -> entry.copy(onSelect = {
                if (sessionId != null) uiScope.launch { model.set(sessionId, HeadsetCommand.NoiseMode(entry.mode)) }
            })

            is TrayMenuEntry.Label -> if (remoteSource != null && entry.text == summary.title) {
                entry.copy(text = "本机 · ${entry.text}")
            } else {
                entry
            }

            TrayMenuEntry.Separator -> entry
        }
    }

    when (host) {
        TrayPanelHost.ANCHORED_POPUP -> {
            val menu = entries { trayState.show() }
            TrayApp(
                icon = icon,
                tooltip = tooltip,
                state = trayState,
                windowSize = trayPanelSize,
                windowsTitle = "OpenRose",
                menu = { render(menu) },
            ) {
                CompositionLocalProvider(LocalCirculationVisible provides anchoredOpen) {
                    TrayPanel(
                        model,
                        selection,
                        onOpenApp = {
                            trayState.hide()
                            openApp(it)
                        },
                        circulation = circulation,
                        sound = sound,
                        remoteHeadsets = peers,
                        onContentHeight = { trayState.setWindowSize(DpSize(trayPanelSize.width, it)) },
                    )
                }
            }
        }

        TrayPanelHost.WINDOW -> {
            val menu = entries { panelOpen = true }
            Tray(
                icon = icon,
                tooltip = tooltip,
                onMenuOpened = { uiScope.launch { menuRefresh++ } },
                primaryAction = {
                    uiScope.launch { panelOpen = !panelOpen }
                },
            ) { render(menu) }
            if (panelOpen) {
                TrayPanelWindow(
                    model = model,
                    selection = selection,
                    circulation = circulation,
                    sound = sound,
                    peers = peers,
                    onDismiss = { panelOpen = false },
                    onOpenApp = {
                        panelOpen = false
                        openApp(it)
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

/**
 * Composed only while open: every DecoratedWindow is re-shown when a second instance launches,
 * and that launch must bring back the main window, not the panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NucleusApplicationScope.TrayPanelWindow(
    model: OpenRoseModel,
    selection: PrimaryDeviceSelection,
    circulation: CirculationClient,
    sound: DesktopSoundActions?,
    peers: List<RemoteHeadset>,
    onDismiss: () -> Unit,
    onOpenApp: (WindowDestination?) -> Unit,
) {
    val windowState = rememberWindowState(size = trayPanelSize)
    DecoratedWindow(
        onCloseRequest = onDismiss,
        state = windowState,
        title = TRAY_PANEL_WINDOW_TITLE,
        icon = AppLogo.painter,
        resizable = false,
        alwaysOnTop = true,
        undecorated = true,
        onPreviewKeyEvent = { event ->
            (event.type == KeyEventType.KeyUp && event.key == Key.Escape).also { if (it) onDismiss() }
        },
    ) {
        val window = nucleusWindow
        // Dismiss like a popup once focus stays elsewhere. Focus-follows-mouse (the default on Hyprland
        // and Sway) hands focus to every window the pointer crosses on its way to the panel; it returns
        // when the pointer arrives, so only a lasting loss closes the panel.
        LaunchedEffect(window) {
            window.requestFocus()
            window.focusFlow.first { it }
            window.focusFlow.collectLatest { focused ->
                if (!focused) {
                    delay(FOCUS_LOSS_GRACE_MS)
                    onDismiss()
                }
            }
        }
        // Once the pointer has reached the panel, leaving it dismisses the panel; a click elsewhere
        // cannot be observed on Wayland, and with focus-follows-mouse focus already left on hover.
        var pointerInside by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(pointerInside) {
            if (pointerInside == false) {
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
                selection,
                onOpenApp,
                circulation = circulation,
                sound = sound,
                remoteHeadsets = peers,
                onContentHeight = { windowState.size = DpSize(trayPanelSize.width, it) },
            )
        }
    }
}

internal enum class TrayMenuTarget { RECONNECT, ADD_DEVICE, PANEL, OPEN_APP, EXIT }

/** The 托盘菜单 as data, so both tray APIs render the same entries. */
internal sealed interface TrayMenuEntry {
    data class Label(val text: String) : TrayMenuEntry
    data object Separator : TrayMenuEntry
    data class NoiseMode(
        val mode: HeadsetNoiseMode,
        val label: String,
        val selected: Boolean,
        val enabled: Boolean,
        val onSelect: () -> Unit = {},
    ) : TrayMenuEntry
    data class Action(val label: String, val target: TrayMenuTarget, val onClick: () -> Unit = {}) : TrayMenuEntry
}

private fun remoteMenuEntries(peers: List<RemoteHeadset>, uiScope: CoroutineScope): List<TrayMenuEntry> = buildList {
    peers.forEach { peer ->
        val reading = peer.reading
        val device = peer.deviceName
        if (device != null) {
            add(TrayMenuEntry.Label("$device · ${peer.sourceLabel}"))
            peer.statusLabel?.let { add(TrayMenuEntry.Label(it)) }
            add(TrayMenuEntry.Label(remoteBatteryText(reading)))
            reading.modes.forEach { mode ->
                val entry = TrayMenuEntry.NoiseMode(mode, noiseModeLabel(mode.id), mode == reading.mode, !peer.busy) {
                    uiScope.launch { peer.selectMode(mode) }
                }
                add(entry)
            }
        } else {
            add(TrayMenuEntry.Label("${peer.name} · ${peer.statusLabel}"))
        }
        peer.feedback?.let { add(TrayMenuEntry.Label(it)) }
        add(TrayMenuEntry.Separator)
    }
}

internal fun trayMenuEntries(summary: TraySummary): List<TrayMenuEntry> = buildList {
    add(TrayMenuEntry.Label(summary.title))
    summary.battery?.let { add(TrayMenuEntry.Label(it)) }
    if (summary.noiseModes.isNotEmpty()) {
        add(TrayMenuEntry.Separator)
        summary.noiseModeFeedback?.let { add(TrayMenuEntry.Label(it)) }
        summary.noiseModes.forEach { add(TrayMenuEntry.NoiseMode(it.mode, it.label, it.selected, it.enabled)) }
    }
    if (summary.reconnectTarget != null) add(TrayMenuEntry.Action(summary.reconnectLabel, TrayMenuTarget.RECONNECT))
    if (summary.addDevice) add(TrayMenuEntry.Action("添加耳机", TrayMenuTarget.ADD_DEVICE))
    add(TrayMenuEntry.Separator)
    add(TrayMenuEntry.Action("托盘面板", TrayMenuTarget.PANEL))
    add(TrayMenuEntry.Action("打开 OpenRose", TrayMenuTarget.OPEN_APP))
    add(TrayMenuEntry.Action("退出", TrayMenuTarget.EXIT))
}

private fun TrayMenuBuilder.render(entries: List<TrayMenuEntry>) = entries.forEach { entry ->
    when (entry) {
        is TrayMenuEntry.Label -> Item(entry.text, isEnabled = false)

        TrayMenuEntry.Separator -> Divider()

        // Choosing the checked mode again unchecks it natively; only a different mode is written.
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

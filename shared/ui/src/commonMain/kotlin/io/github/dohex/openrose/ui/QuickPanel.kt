package io.github.dohex.openrose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.circulation.CirculationClient
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import io.github.dohex.openrose.ui.model.settingsFor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt

/** Test tag of the scrim behind the sheet; tapping it dismisses the panel. */
internal const val QUICK_PANEL_SCRIM_TAG = "quick-panel-scrim"

/** Test tag of the drag handle on top of the sheet; dragging it down far or fast enough dismisses the panel. */
internal const val QUICK_PANEL_HANDLE_TAG = "quick-panel-handle"

internal const val QUICK_EQUALIZER_MENU_TAG = "quick-eq-menu"

/** The widest a page's centered column, or the quick panel sheet, grows on a big window. */
internal val ContentMaxWidth = 640.dp
private val SheetCorner = 28.dp
private const val SCRIM_ALPHA = 0.32f

/** A drag released past this share of the sheet's height dismisses it. */
private const val DISMISS_FRACTION = 0.3f

/** A downward fling faster than this dismisses the sheet however far it was dragged. */
private val DismissVelocity = 1000.dp

/**
 * The 快捷面板: a scrim over the whole window and a bottom sheet with the common headset settings. Dragging the
 * handle moves the sheet and lightens the scrim with it. A tap on the scrim, Back, or a drag released far or fast
 * enough slides the sheet out and then calls [onDismiss] once; a shorter drag springs back. The panel stays open
 * after a change. Window insets other than the bottom navigation bar belong to the host.
 */
@Composable
fun QuickPanel(
    model: OpenRoseModel,
    selection: PrimaryDeviceSelection,
    dynamicColor: Boolean,
    onOpenApp: (WindowDestination?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    circulation: CirculationClient? = null,
) {
    val state by model.state.collectAsState()
    val uiSettings by model.uiSettings.collectAsState()
    val dark = uiSettings.appearance.isDark()
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val entryState = remember { MutableTransitionState(reduceMotion).apply { targetState = true } }
    val entry = rememberTransition(entryState, "quickPanelEntry")
    val dropped = remember { Animatable(0f, visibilityThreshold = 0.001f).apply { updateBounds(0f, 1f) } }
    var sheetHeight by remember { mutableIntStateOf(0) }
    var exitStart by remember { mutableStateOf<EntryFrame?>(null) }
    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark, dynamicColor) {
        val style = sheetStyle()
        val height by entry.animateFloat({ style.spatial }, "sheetHeight") { if (it) 1f else 0f }
        val opacity by entry.animateFloat({ style.effects }, "scrimOpacity") { if (it) 1f else 0f }
        fun entryHeight() = exitStart?.height ?: height.coerceIn(0f, 1f)
        fun entryOpacity() = exitStart?.opacity ?: opacity.coerceIn(0f, 1f)
        fun visibleHeight() = (sheetHeight * entryHeight()).coerceAtLeast(1f)
        suspend fun settle(target: Float, velocity: Float = 0f) {
            if (reduceMotion) {
                dropped.snapTo(target)
            } else {
                dropped.animateTo(target, style.spatial, initialVelocity = velocity / visibleHeight())
            }
        }

        fun close(velocity: Float = 0f) {
            if (exitStart != null) return
            // An early dismissal continues from the height and opacity already shown.
            exitStart = EntryFrame(entryHeight(), entryOpacity())
            scope.launch {
                settle(1f, velocity)
                dismiss()
            }
        }

        PlatformBackHandler(enabled = exitStart == null) { close() }
        Box(modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(QUICK_PANEL_SCRIM_TAG)
                    .drawBehind {
                        drawRect(style.scrim, alpha = entryOpacity() * (1f - dropped.value))
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "关闭快捷面板",
                        onClick = { close() },
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset {
                        val visible = entryHeight() * (1f - dropped.value)
                        IntOffset(0, (sheetHeight * (1f - visible)).roundToInt())
                    }
                    .onSizeChanged { sheetHeight = it.height }
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .clip(style.shape)
                    .background(style.container)
                    // Taps on the sheet are its own; only the scrim dismisses.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SheetHandle(
                    isClosing = { exitStart != null },
                    dragBy = { delta -> dropped.snapTo(dropped.value + delta / visibleHeight()) },
                    pastDismissDistance = { dropped.value * entryHeight() > DISMISS_FRACTION },
                    rebound = { velocity -> settle(0f, velocity) },
                    close = ::close,
                )
                QuickPanelContent(model, state, selection, circulation, onOpenApp)
            }
        }
    }
}

private val sheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

private data class EntryFrame(val height: Float, val opacity: Float)

private class SheetStyle(
    val shape: Shape,
    val container: Color,
    val scrim: Color,
    val spatial: FiniteAnimationSpec<Float>,
    val effects: FiniteAnimationSpec<Float>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun sheetStyle() = when (LocalInterfaceStyle.current) {
    InterfaceStyle.MATERIAL3 -> SheetStyle(
        BottomSheetDefaults.ExpandedShape,
        BottomSheetDefaults.ContainerColor,
        BottomSheetDefaults.ScrimColor,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    InterfaceStyle.MIUIX -> SheetStyle(
        RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
        MiuixTheme.colorScheme.surface,
        Color.Black.copy(alpha = SCRIM_ALPHA),
        sheetSpring,
        sheetSpring,
    )
}

/** The visible bar is small; the whole width above the content takes the drag. A screen reader gets the same exit as a button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHandle(
    isClosing: () -> Boolean,
    dragBy: suspend (Float) -> Unit,
    pastDismissDistance: () -> Boolean,
    rebound: suspend (Float) -> Unit,
    close: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissVelocity = with(LocalDensity.current) { DismissVelocity.toPx() }
    Box(
        Modifier
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch { if (!isClosing()) dragBy(delta) }
                },
                orientation = Orientation.Vertical,
                enabled = !isClosing(),
                onDragStopped = { velocity ->
                    if (isClosing()) return@draggable
                    if (pastDismissDistance() || velocity > dismissVelocity) close(velocity) else rebound(velocity)
                },
            )
            .testTag(QUICK_PANEL_HANDLE_TAG)
            .fillMaxWidth()
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "快捷面板拖动条"
                onClick(label = "关闭快捷面板") {
                    close(0f)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MATERIAL3 -> BottomSheetDefaults.DragHandle()

            InterfaceStyle.MIUIX -> Box(
                Modifier.padding(vertical = 8.dp).width(36.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(appColor(AppColorRole.Summary).copy(alpha = 0.4f)),
            )
        }
    }
}

@Composable
private fun QuickPanelContent(
    model: OpenRoseModel,
    state: ControlState,
    selection: PrimaryDeviceSelection,
    circulation: CirculationClient?,
    onOpenApp: (WindowDestination?) -> Unit,
) {
    val summary = rememberTraySummary(state, selection)
    val cards = rememberHeadsetCards(state, selection, circulation)
    val device = state.activeDevice()?.takeIf { summary.connected }

    if (cards.showLocal) {
        if (cards.local.remote != null) {
            AdditionalHeadsetCard(cards.local)
        } else {
            QuickPanelStatusCard(model, state, summary, cards.local)
        }
    }
    cards.others.forEach { source -> AdditionalHeadsetCard(source, onRecoverControl = model::connect) }

    if (device != null) {
        val onSet = model.settingsFor(device.sessionId)
        if (cards.local.remote == null) {
            NoiseControlCard(state, device, showLevelControls = false, onSet = onSet)
        }
        QuickEqualizerMenu(state, device, onSet)
        if (DeviceSetting.GAME_MODE in device.capabilities.writableSettings) {
            AppDashboardCard(compact = true) {
                SettingToggleChoices(
                    "游戏模式",
                    DeviceField.GAME_MODE,
                    HeadsetCommand::GameMode,
                    device,
                    state.canWrite(device, DeviceSetting.GAME_MODE),
                    onSet,
                    pendingToggle = state.pendingToggle(device, DeviceSetting.GAME_MODE),
                    icon = RoseIcon.GAME,
                )
            }
        }
        // The diagnostics live in the main window.
        SettingFeedback(
            state = state,
            device = device,
            showPending = { it != DeviceSetting.NOISE_MODE && it != DeviceSetting.GAME_MODE },
            onViewDetails = { onOpenApp(WindowDestination.DIAGNOSTICS) },
        )
    }

    QuickPanelActions(summary.addDevice, onOpenApp)
}

/** The headphones' title, connection progress or reason it is not connected, and the battery strip when connected. */
@Composable
private fun QuickPanelStatusCard(model: OpenRoseModel, state: ControlState, summary: TraySummary, source: HeadsetCardSource) {
    val device = state.activeDevice()?.takeIf { summary.connected }
    AppDashboardCard {
        val title = if (source.sourceLabel != null) summary.deviceName ?: summary.title else summary.title
        AppText(title, role = AppTextRole.Title)
        source.sourceLabel?.let { AppText(it, role = AppTextRole.Summary) }
        QuickConnectionStatus(state, summary, source)
        if (device != null) {
            BatteryStrip(device, initializing = false, showUnknown = state.platform.hostPlatform.isDesktop)
        }
        PanelConnectionActions(model, state, summary, source, compact = false)
    }
}

@Composable
private fun QuickConnectionStatus(state: ControlState, summary: TraySummary, source: HeadsetCardSource) {
    val connecting = !summary.connected && state.isConnectingForTray()
    when {
        connecting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppBusyIndicator()
            AppText(
                if (source.hasLocalAudio) audioWithoutControlStatus(true) else state.connection.phaseOrNull()?.displayText() ?: "正在连接…",
                role = AppTextRole.Summary,
                color = appColor(AppColorRole.Primary),
            )
        }

        source.hasLocalAudio && !summary.connected -> AppText(
            state.lastError?.message ?: audioWithoutControlStatus(false),
            role = AppTextRole.Summary,
            color = if (state.lastError != null) appColor(AppColorRole.Error) else null,
        )

        !source.offersTransfer && summary.connectionHint != null -> AppText(
            summary.connectionHint,
            role = AppTextRole.Summary,
            color = if (state.lastError != null && state.platform.connectionBlockReason == null) appColor(AppColorRole.Error) else null,
        )
    }
}

@Composable
private fun QuickPanelActions(offerAdd: Boolean, onOpenApp: (WindowDestination?) -> Unit) {
    val inset = if (LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3) 16.dp else 12.dp
    Column(
        Modifier.fillMaxWidth().padding(horizontal = inset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (offerAdd) {
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

/** The row and the menu checkmark both follow the headset's confirmed mode. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickEqualizerMenu(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val options = device.writablePresetOptions()
    if (options.isEmpty()) return
    val blocked = state.writeBlockedReason(device, DeviceSetting.EQ)
    val enabled = blocked == null
    val confirmed = device.confirmedText(DeviceField.EQ)
    var expanded by remember(device.sessionId, enabled) { mutableStateOf(false) }
    fun select(preset: String) {
        if (!enabled || !expanded) return
        expanded = false
        if (preset != confirmed) onSet(HeadsetCommand.EqualizerPreset(preset))
    }

    AppDashboardCard(compact = true) {
        Box {
            val valueColor = appColor(if (enabled) AppColorRole.Summary else AppColorRole.DisabledContent)
            Row(
                Modifier.fillMaxWidth().testTag(QUICK_EQUALIZER_MENU_TAG)
                    .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("均衡器", color = if (enabled) null else valueColor)
                AppText(
                    confirmedChoiceValue(DeviceField.EQ, device),
                    Modifier.weight(1f),
                    role = AppTextRole.Summary,
                    color = valueColor,
                    textAlign = TextAlign.End,
                )
                ExpansionIcon(expanded, Modifier.size(20.dp), tint = valueColor)
            }
            when (LocalInterfaceStyle.current) {
                InterfaceStyle.MIUIX -> WindowListPopup(
                    show = expanded,
                    alignment = PopupPositionProvider.Align.End,
                    enableWindowDim = false,
                    onDismissRequest = { expanded = false },
                ) {
                    ListPopupColumn {
                        options.forEachIndexed { index, option ->
                            DropdownImpl(
                                text = option.label,
                                optionSize = options.size,
                                isSelected = option.value == confirmed,
                                index = index,
                                onSelectedIndexChange = { select(option.value) },
                            )
                        }
                    }
                }

                InterfaceStyle.MATERIAL3 -> DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShapes(),
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    ) {
                        options.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                selected = option.value == confirmed,
                                onClick = { select(option.value) },
                                text = { Text(option.label) },
                                selectedLeadingIcon = { AppIcon(RoseIcon.CHECK) },
                                shapes = MenuDefaults.itemShape(index, options.size),
                            )
                        }
                    }
                }
            }
        }
        blocked?.let { AppText(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), role = AppTextRole.Footnote) }
    }
}

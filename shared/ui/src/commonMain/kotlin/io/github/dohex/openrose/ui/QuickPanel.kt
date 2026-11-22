package io.github.dohex.openrose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val dismissVelocity = with(LocalDensity.current) { DismissVelocity.toPx() }
    // How far the sheet sits below its resting place, from a drag or the exit; the scrim fades along with it.
    // Never above rest: the bound stops drags and a spring that an upward fling would carry past it alike.
    val drop = remember { Animatable(0f, visibilityThreshold = 1f).apply { updateBounds(lowerBound = 0f) } }
    var sheetHeight by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()

    // Every way out slides the sheet off first; the host only hears about it once the sheet has left the screen.
    // With animations removed in the system settings the sheet jumps instead of sliding.
    suspend fun settle(target: Float, velocity: Float = 0f) {
        if (reduceMotion) drop.snapTo(target) else drop.animateTo(target, sheetSpring, initialVelocity = velocity)
    }

    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
        scope.launch {
            settle(sheetHeight.toFloat(), velocity)
            dismiss()
        }
    }
    PlatformBackHandler(enabled = !closing) { close() }
    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark, dynamicColor) {
        Box(modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(QUICK_PANEL_SCRIM_TAG)
                    .drawBehind {
                        val shown = if (sheetHeight > 0) 1f - drop.value / sheetHeight else 1f
                        drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * shown.coerceIn(0f, 1f)))
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "关闭快捷面板",
                        onClick = { close() },
                    ),
            )
            AnimatedVisibility(
                visibleState = entered,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (reduceMotion) EnterTransition.None else slideInVertically { it },
            ) {
                Column(
                    Modifier
                        .offset { IntOffset(0, drop.value.roundToInt()) }
                        .onSizeChanged { sheetHeight = it.height }
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
                        .background(sheetBackground())
                        // Taps on the sheet are its own; only the scrim dismisses.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SheetHandle(
                        Modifier.draggable(
                            state = rememberDraggableState { delta ->
                                scope.launch { drop.snapTo(drop.value + delta) }
                            },
                            orientation = Orientation.Vertical,
                            enabled = !closing,
                            onDragStopped = { velocity ->
                                if (drop.value > sheetHeight * DISMISS_FRACTION || velocity > dismissVelocity) {
                                    close(velocity)
                                } else {
                                    settle(0f, velocity)
                                }
                            },
                        ),
                        onClose = { close() },
                    )
                    QuickPanelContent(model, state, selection, circulation, onOpenApp)
                }
            }
        }
    }
}

private val sheetSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

/** The sheet follows the theme in use, so 动态取色 reaches its background too. */
@Composable
private fun sheetBackground(): Color = when (LocalInterfaceStyle.current) {
    InterfaceStyle.MIUIX -> MiuixTheme.colorScheme.surface
    InterfaceStyle.MATERIAL3 -> MaterialTheme.colorScheme.background
}

/** The visible bar is small; the whole width above the content takes the drag. A screen reader gets the same exit as a button. */
@Composable
private fun SheetHandle(modifier: Modifier, onClose: () -> Unit) {
    Box(
        modifier
            .testTag(QUICK_PANEL_HANDLE_TAG)
            .fillMaxWidth()
            .height(20.dp)
            .semantics {
                role = Role.Button
                contentDescription = "快捷面板拖动条"
                onClick(label = "关闭快捷面板") {
                    onClose()
                    true
                }
            }
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(appColor(AppColorRole.Summary).copy(alpha = 0.4f)),
        )
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
        if (cards.local.remote == null && device.capabilities.noiseControl != null) {
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
                    icon = RoseIcon.GAME,
                )
            }
        }
        // The diagnostics live in the main window.
        SettingFeedback(state, device, onViewDetails = { onOpenApp(WindowDestination.DIAGNOSTICS) })
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
                AppIcon(RoseIcon.CHEVRON, Modifier.size(20.dp).rotate(if (expanded) -90f else 90f), tint = valueColor)
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
                    DropdownMenuGroup(shapes = MenuDefaults.groupShapes(), modifier = Modifier.verticalScroll(rememberScrollState())) {
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

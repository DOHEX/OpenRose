package io.github.dohex.openrose.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowDestination
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

/** Test tag of the scrim behind the sheet; tapping it dismisses the panel. */
internal const val QUICK_PANEL_SCRIM_TAG = "quick-panel-scrim"

/** Test tag of the drag handle on top of the sheet; dragging it down far or fast enough dismisses the panel. */
internal const val QUICK_PANEL_HANDLE_TAG = "quick-panel-handle"

/** Test tag of the equalizer choice for [preset]. */
internal fun quickEqualizerTag(preset: String): String = "quick-eq-$preset"

private val SheetMaxWidth = 640.dp
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

    // Every way out slides the sheet off first; the host only hears about it once the sheet has left the screen.
    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
        scope.launch {
            drop.animateTo(sheetHeight.toFloat(), sheetSpring, initialVelocity = velocity)
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
                enter = slideInVertically { it },
            ) {
                Column(
                    Modifier
                        .offset { IntOffset(0, drop.value.roundToInt()) }
                        .onSizeChanged { sheetHeight = it.height }
                        .widthIn(max = SheetMaxWidth)
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
                                    drop.animateTo(0f, sheetSpring, initialVelocity = velocity)
                                }
                            },
                        ),
                    )
                    QuickPanelContent(model, state, selection, onOpenApp)
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

/** The visible bar is small; the whole width above the content takes the drag. */
@Composable
private fun SheetHandle(modifier: Modifier) {
    Box(
        modifier.testTag(QUICK_PANEL_HANDLE_TAG).fillMaxWidth().height(20.dp).padding(top = 8.dp),
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
    onOpenApp: (WindowDestination?) -> Unit,
) {
    val summary = traySummary(state, selection)
    val device = state.activeDevice()?.takeIf { summary.connected }

    TrayStatusCard(state, summary)

    if (device != null) {
        if (device.capabilities.noiseControl != null) {
            NoiseControlCard(state, device, showLevelControls = false, onSet = model::set)
        }
        EqualizerChoices(state, device, model::set)
        if (DeviceSetting.GAME_MODE in device.capabilities.writableSettings) {
            AppDashboardCard(compact = true) {
                SettingToggleChoices(
                    "游戏模式",
                    DeviceField.GAME_MODE,
                    DeviceSetting.GAME_MODE,
                    device,
                    state.isReady(device) && !state.hasPendingSetting(device),
                    model::set,
                    icon = RoseIcon.GAME,
                )
            }
        }
        // The diagnostics live in the main window.
        SettingFeedback(state, device, showPending = true, onViewDetails = { onOpenApp(WindowDestination.DIAGNOSTICS) })
    }

    TrayActions(model, state, summary, onOpenApp)
}

/** One horizontally scrolling row of the writable presets; only the device's read-back is ever selected. */
@Composable
private fun EqualizerChoices(state: ControlState, device: DeviceState, onSet: (DeviceSetting, FieldValue) -> Unit) {
    val presets = device.writableEqualizerPresets()
    if (presets.isEmpty()) return
    val pending = state.hasPendingSetting(device)
    val enabled = state.isReady(device) && !pending
    val confirmed = (device.knownValue(DeviceField.EQ) as? FieldValue.Text)?.value
    AppDashboardCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("均衡器", Modifier.weight(1f), role = AppTextRole.Title)
            val headerStatus = when {
                pending -> "等待设备确认"
                confirmed == null -> "当前状态未知"
                else -> null
            }
            headerStatus?.let { AppText(it, role = AppTextRole.Summary) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { preset ->
                QuickChoice(
                    label = equalizerLabel(preset, device.profile),
                    selected = preset == confirmed,
                    enabled = enabled,
                    tag = quickEqualizerTag(preset),
                    onClick = { if (preset != confirmed) onSet(DeviceSetting.EQ, FieldValue.Text(preset)) },
                )
            }
        }
    }
}

@Composable
private fun QuickChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTextButton(
            label,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag(tag).semantics {
                this.selected = selected
                role = Role.RadioButton
                if (!enabled) disabled()
            },
            colors = if (selected) MiuixButtonDefaults.textButtonColorsPrimary() else MiuixButtonDefaults.textButtonColors(),
        )

        InterfaceStyle.MATERIAL3 -> FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

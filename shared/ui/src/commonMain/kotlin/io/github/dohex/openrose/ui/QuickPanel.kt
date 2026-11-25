package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
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
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
internal const val QUICK_EQUALIZER_MENU_TAG = "quick-eq-menu"

internal val ContentMaxWidth = 640.dp

@Composable
fun QuickPanel(
    model: OpenRoseModel,
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
    ProvideInterfaceStyle(uiSettings.interfaceStyle, dark, dynamicColor) {
        Box(modifier.fillMaxSize()) {
            when (uiSettings.interfaceStyle) {
                InterfaceStyle.MIUIX -> MiuixQuickPanelSheet(onDismiss = { dismiss() }) {
                    QuickPanelContent(model, state, circulation, onOpenApp)
                }

                InterfaceStyle.MATERIAL3 -> MaterialQuickPanelSheet(onDismiss = { dismiss() }) {
                    QuickPanelContent(model, state, circulation, onOpenApp)
                }
            }
        }
    }
}

@Composable
private fun MiuixQuickPanelSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    var dismissed by remember { mutableStateOf(false) }
    WindowBottomSheet(
        show = show,
        backgroundColor = MiuixTheme.colorScheme.surface,
        sheetMaxWidth = ContentMaxWidth,
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = { show = false },
        onDismissFinished = {
            if (!dismissed) {
                dismissed = true
                onDismiss()
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialQuickPanelSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var show by remember { mutableStateOf(true) }
    var dismissed by remember { mutableStateOf(false) }
    if (!show) return
    ModalBottomSheet(
        onDismissRequest = {
            show = false
            if (!dismissed) {
                dismissed = true
                onDismiss()
            }
        },
        sheetState = sheetState,
        sheetMaxWidth = ContentMaxWidth,
        shape = BottomSheetDefaults.ExpandedShape,
        containerColor = BottomSheetDefaults.ContainerColor,
        scrimColor = BottomSheetDefaults.ScrimColor,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun QuickPanelContent(
    model: OpenRoseModel,
    state: ControlState,
    circulation: CirculationClient?,
    onOpenApp: (WindowDestination?) -> Unit,
) {
    val summary = rememberTraySummary(state)
    val cards = rememberHeadsetCards(state, circulation)
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
        SettingFeedback(
            state = state,
            device = device,
            showPending = { it != DeviceSetting.NOISE_MODE && it != DeviceSetting.GAME_MODE },
            onViewDetails = { onOpenApp(WindowDestination.DIAGNOSTICS) },
        )
    }

    QuickPanelActions(summary.addDevice, onOpenApp)
}

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
            color = if (state.lastError != null && state.connectionBlockReason == null) appColor(AppColorRole.Error) else null,
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

@Composable
private fun QuickEqualizerMenu(state: ControlState, device: DeviceState, onSet: (HeadsetCommand) -> Unit) {
    val options = device.writablePresetOptions()
    if (options.isEmpty()) return
    val blocked = state.writeBlockedReason(device, DeviceSetting.EQ)
    val enabled = blocked == null
    val confirmed = device.confirmedText(DeviceField.EQ)
    val valueText = confirmedChoiceValue(DeviceField.EQ, device)
    val select: (String) -> Unit = { preset ->
        if (enabled && preset != confirmed) onSet(HeadsetCommand.EqualizerPreset(preset))
    }

    AppDashboardCard(compact = true) {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> MiuixQuickEqualizerRow(options, confirmed, valueText, enabled, select)
            InterfaceStyle.MATERIAL3 -> MaterialQuickEqualizerRow(device.sessionId, options, confirmed, valueText, enabled, select)
        }
        blocked?.let { AppText(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), role = AppTextRole.Footnote) }
    }
}

@Composable
private fun MiuixQuickEqualizerRow(
    options: List<AppOption<String>>,
    confirmed: String?,
    valueText: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val hasSelected = confirmed != null && options.any { it.value == confirmed }
    val entry = remember(options, confirmed, enabled, onSelect) {
        DropdownEntry(
            options.map { option ->
                DropdownItem(
                    text = option.label,
                    selected = confirmed != null && option.value == confirmed,
                    enabled = enabled,
                    onClick = { onSelect(option.value) },
                )
            },
        )
    }
    WindowDropdownPreference(
        entry = entry,
        title = "均衡器",
        modifier = Modifier
            .heightIn(min = 60.dp)
            .testTag(QUICK_EQUALIZER_MENU_TAG)
            .semantics { if (!enabled) disabled() },
        summary = valueText.takeIf { !hasSelected },
        startAction = { AppIcon(RoseIcon.EQUALIZER) },
        enabled = enabled,
        showValue = hasSelected,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialQuickEqualizerRow(
    sessionId: Any,
    options: List<AppOption<String>>,
    confirmed: String?,
    valueText: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(sessionId, enabled) { mutableStateOf(false) }
    val valueColor = appColor(if (enabled) AppColorRole.Summary else AppColorRole.DisabledContent)
    Box {
        ListItem(
            onClick = { expanded = true },
            modifier = Modifier.testTag(QUICK_EQUALIZER_MENU_TAG).semantics { role = Role.DropdownList },
            enabled = enabled,
            leadingContent = { AppIcon(RoseIcon.EQUALIZER) },
            verticalAlignment = Alignment.CenterVertically,
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(valueText)
                    ExpansionIcon(expanded, Modifier.size(20.dp), tint = valueColor)
                }
            },
            colors = rowColors(),
        ) { Text("均衡器") }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShapes(),
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        selected = option.value == confirmed,
                        onClick = {
                            expanded = false
                            onSelect(option.value)
                        },
                        text = { Text(option.label) },
                        selectedLeadingIcon = { AppIcon(RoseIcon.CHECK) },
                        shapes = MenuDefaults.itemShape(index, options.size),
                    )
                }
            }
        }
    }
}

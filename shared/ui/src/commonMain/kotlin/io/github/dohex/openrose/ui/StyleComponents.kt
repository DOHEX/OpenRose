@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.MIUIX }

internal val LocalChoiceDialog = staticCompositionLocalOf<MutableState<String?>?> { null }

internal enum class RowPlacement { Page, Segmented, Contained }
internal val LocalRowPlacement = staticCompositionLocalOf { RowPlacement.Page }

enum class AppColorRole { Primary, OnSurface, Summary, Error, Divider, SelectedContainer, SelectedContent, DisabledContent }
enum class AppTextRole { Display, Title, Body, Summary, Footnote }
enum class AppButtonKind { Primary, Secondary }

data class AppOption<T>(
    val value: T,
    val label: String,
    val contentDescription: String? = null,
    val testTag: String? = null,
    val disabledReason: String? = null,
)

class AppTitleAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

@Composable
fun appColor(role: AppColorRole): Color = when (LocalInterfaceStyle.current) {
    InterfaceStyle.MIUIX -> miuixColor(role)
    InterfaceStyle.MATERIAL3 -> materialColor(role)
}

@Composable
fun ProvideInterfaceStyle(style: InterfaceStyle, dark: Boolean, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInterfaceStyle provides style, LocalThemeTone provides ThemeTone(dark, dynamicColor)) {
        when (style) {
            InterfaceStyle.MIUIX -> MiuixTheme(
                colors = if (dynamicColor) platformDynamicColors(dark) else miuixColors(dark),
                content = content,
            )

            InterfaceStyle.MATERIAL3 -> MaterialTheme(
                colorScheme = (if (dynamicColor) platformMaterialDynamicColors(dark) else null) ?: materialColors(dark),
                motionScheme = MotionScheme.expressive(),
                content = content,
            )
        }
    }
}

fun interfaceBackgroundColor(style: InterfaceStyle, dark: Boolean): Color = when (style) {
    InterfaceStyle.MIUIX -> miuixColors(dark).surface
    InterfaceStyle.MATERIAL3 -> materialColors(dark).background
}

private fun miuixColors(dark: Boolean) = if (dark) miuixDarkColorScheme() else miuixLightColorScheme()
private fun materialColors(dark: Boolean) = if (dark) darkColorScheme() else expressiveLightColorScheme()

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    role: AppTextRole = AppTextRole.Body,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val textModifier = if (role == AppTextRole.Display || role == AppTextRole.Title) modifier.semantics { heading() } else modifier
    val textColor = color ?: appColor(
        if (role == AppTextRole.Summary || role == AppTextRole.Footnote) AppColorRole.Summary else AppColorRole.OnSurface,
    )
    val overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixText(
            text,
            modifier = textModifier,
            color = textColor,
            maxLines = maxLines,
            textAlign = textAlign,
            overflow = overflow,
            style = miuixTextStyle(role),
        )

        InterfaceStyle.MATERIAL3 -> Text(
            text,
            modifier = textModifier,
            color = textColor,
            maxLines = maxLines,
            textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified,
            overflow = overflow,
            style = materialTextStyle(role),
        )
    }
}

@Composable
fun AppGroup(
    title: String? = null,
    header: (@Composable () -> Unit)? = null,
    footnote: String? = null,
    footnoteIcon: RoseIcon? = null,
    titleAction: AppTitleAction? = null,
    plain: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> {
                title?.let { MiuixGroupTitle(it, titleAction) }
                header?.let {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                        it()
                    }
                }
                if (plain) {
                    Column(Modifier.fillMaxWidth().padding(start = 28.dp), content = content)
                } else {
                    MiuixCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp), content = content)
                }
            }

            InterfaceStyle.MATERIAL3 -> {
                title?.let { MaterialGroupTitle(it, titleAction) }
                header?.let {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                        it()
                    }
                }
                if (plain) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp), content = content)
                } else {
                    MaterialSegmentedList(content)
                }
            }
        }
        footnote?.let { text ->
            Spacer(Modifier.height(8.dp))
            if (footnoteIcon != null) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AppIcon(
                        icon = footnoteIcon,
                        modifier = Modifier.padding(top = 2.dp).size(14.dp),
                        tint = appColor(AppColorRole.Summary),
                    )
                    AppText(text, role = AppTextRole.Footnote)
                }
            } else {
                AppText(text, Modifier.padding(horizontal = 16.dp), role = AppTextRole.Footnote)
            }
        }
    }
}

@Composable
private fun MiuixGroupTitle(label: String, action: AppTitleAction?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SmallTitle(label, Modifier.weight(1f).semantics { heading() })
        action?.let {
            MiuixTextButton(
                text = it.label,
                onClick = it.onClick,
                modifier = Modifier.padding(end = 16.dp),
                enabled = it.enabled,
                cornerRadius = 8.dp,
                minWidth = 0.dp,
                minHeight = 0.dp,
                colors = MiuixButtonDefaults.textButtonColors(
                    color = Color.Transparent,
                    disabledColor = Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary,
                    disabledTextColor = MiuixTheme.colorScheme.disabledOnSurface,
                ),
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                textStyle = MiuixTheme.textStyles.subtitle,
            )
        }
    }
}

@Composable
private fun MaterialGroupTitle(label: String, action: AppTitleAction?) {
    val padding = if (action == null) PaddingValues(16.dp, 8.dp) else PaddingValues(start = 16.dp, end = 4.dp)
    Row(Modifier.fillMaxWidth().padding(padding), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f).semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        action?.let {
            TextButton(
                onClick = it.onClick,
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
                modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
                enabled = it.enabled,
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
            ) { Text(it.label) }
        }
    }
}

@Composable
private fun MaterialSegmentedList(content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalRowPlacement provides RowPlacement.Segmented) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content,
        )
    }
}

@Composable
internal fun AppDashboardCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val padding = if (compact) 0.dp else 16.dp
    val spacing = if (compact) 0.dp else 12.dp
    CompositionLocalProvider(LocalRowPlacement provides RowPlacement.Contained) {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> MiuixCard(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            }

            InterfaceStyle.MATERIAL3 -> Card(
                modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.largeIncreased,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.elevatedCardElevation(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            }
        }
    }
}

@Composable
internal fun rowColors(): ListItemColors = when (LocalRowPlacement.current) {
    RowPlacement.Page -> ListItemDefaults.colors()

    RowPlacement.Segmented -> MaterialTheme.colorScheme.surfaceContainer.let { tile ->
        ListItemDefaults.segmentedColors(containerColor = tile, disabledContainerColor = tile)
    }

    RowPlacement.Contained -> ListItemDefaults.colors(
        containerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        selectedContainerColor = Color.Transparent,
    )
}

@Composable
private fun staticRowModifier(): Modifier = if (LocalRowPlacement.current == RowPlacement.Segmented) {
    Modifier.clip(ListItemDefaults.shapes().shape)
} else {
    Modifier
}

@Composable
private fun rowArrangement(): Arrangement.Vertical = if (LocalRowPlacement.current == RowPlacement.Segmented) {
    Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
} else {
    Arrangement.Top
}

private fun iconAction(icon: RoseIcon?): (@Composable () -> Unit)? = icon?.let { { AppIcon(it) } }

private val RowAlignment = Alignment.CenterVertically

@Composable
fun AppArrowRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    icon: RoseIcon? = null,
    endIcon: RoseIcon? = null,
    onClick: () -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (endIcon == null) {
            ArrowPreference(
                title,
                summary = summary,
                startAction = iconAction(icon),
                endActions = { value?.let { AppText(it, role = AppTextRole.Summary) } },
                onClick = onClick,
            )
        } else {
            BasicComponent(
                title = title,
                summary = summary,
                startAction = iconAction(icon),
                endActions = {
                    value?.let { AppText(it, Modifier.padding(end = 8.dp), role = AppTextRole.Summary) }
                    AppIcon(
                        endIcon,
                        Modifier.size(18.dp).align(Alignment.CenterVertically),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
                onClick = onClick,
            )
        }

        InterfaceStyle.MATERIAL3 -> ListItem(
            onClick = onClick,
            modifier = Modifier.semantics { role = Role.Button },
            leadingContent = iconAction(icon),
            verticalAlignment = RowAlignment,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    value?.let { Text(it) }
                    AppIcon(endIcon ?: RoseIcon.CHEVRON)
                }
            },
            supportingContent = summary?.let { { Text(it) } },
            colors = rowColors(),
        ) { Text(title) }
    }
}

@Composable
fun AppInfoRow(label: String, value: String, valueColor: Color? = null, summary: String? = null) {
    val trailing = value.length <= 32 && '\n' !in value
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> when {
            trailing -> BasicComponent(
                title = label,
                summary = summary,
                endActions = { AppText(value, role = AppTextRole.Summary, color = valueColor) },
            )

            valueColor != null -> BasicComponent(
                title = label,
                summary = summary,
                bottomAction = { AppText(value, role = AppTextRole.Summary, color = valueColor) },
            )

            else -> BasicComponent(title = label, summary = listOfNotNull(value, summary).joinToString("\n"))
        }

        InterfaceStyle.MATERIAL3 -> ListItem(
            headlineContent = { Text(label) },
            supportingContent = if (trailing) {
                summary?.let { { Text(it) } }
            } else {
                {
                    Column {
                        Text(value, color = valueColor ?: Color.Unspecified)
                        summary?.let { Text(it) }
                    }
                }
            },
            trailingContent = if (trailing) {
                { Text(value, color = valueColor ?: Color.Unspecified) }
            } else {
                null
            },
            modifier = staticRowModifier(),
            colors = rowColors(),
        )
    }
}

@Composable
fun AppSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    icon: RoseIcon? = null,
    onChange: (Boolean) -> Unit,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> SwitchPreference(
            checked = checked,
            onCheckedChange = onChange,
            title = title,
            summary = summary,
            startAction = iconAction(icon),
            enabled = enabled,
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            onClick = { onChange(!checked) },
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
            enabled = enabled,
            leadingContent = iconAction(icon),
            verticalAlignment = RowAlignment,
            trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
            supportingContent = summary?.let { { Text(it) } },
            colors = rowColors(),
        ) { Text(title) }
    }
}

@Composable
fun <T> AppRadioGroup(
    options: List<AppOption<T>>,
    selected: T?,
    enabled: Boolean = true,
    pending: T? = null,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup(), verticalArrangement = rowArrangement()) {
        options.forEach { option ->
            RadioOption(
                option = option,
                chosen = selected != null && option.value == selected,
                writing = pending != null && option.value == pending,
                enabled = enabled && option.disabledReason == null,
                onSelect = { onSelect(option.value) },
            )
        }
    }
}

@Composable
private fun <T> RadioOption(option: AppOption<T>, chosen: Boolean, writing: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val modifier = Modifier.semantics {
        this.selected = chosen
        option.contentDescription?.let { contentDescription = it }
        if (!enabled) disabled()
    }.let { base -> option.testTag?.let { base.testTag(it) } ?: base }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> RadioButtonPreference(
            option.label,
            chosen,
            onClick = { if (enabled) onSelect() },
            modifier = modifier,
            summary = option.disabledReason,
            endActions = if (writing) {
                { AppBusyIndicator(20.dp, "正在设为 ${option.label}") }
            } else {
                null
            },
            enabled = enabled,
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            selected = chosen,
            onClick = onSelect,
            modifier = modifier,
            enabled = enabled,
            leadingContent = { RadioButton(chosen, onClick = null, enabled = enabled) },
            verticalAlignment = RowAlignment,
            supportingContent = option.disabledReason?.let { { Text(it) } },
            trailingContent = if (writing) {
                { AppBusyIndicator(20.dp, "正在设为 ${option.label}") }
            } else {
                null
            },
            colors = rowColors(),
        ) { Text(option.label) }
    }
}

@Composable
private fun MiuixChoicePopup(
    isOpen: Boolean,
    setOpen: (Boolean) -> Unit,
    dropdownFits: Boolean = true,
    dropdown: @Composable ((Boolean) -> Unit) -> Unit,
    dialog: @Composable () -> Unit,
) {
    var openedHere by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(isOpen) {
        if (!isOpen && openedHere) {
            openedHere = false
            generation++
        }
    }
    if (dropdownFits && (!isOpen || openedHere)) {
        key(generation) {
            dropdown { expanded ->
                openedHere = expanded
                setOpen(expanded)
            }
        }
    } else {
        dialog()
    }
}

@Composable
fun <T> AppChoiceRow(
    title: String,
    valueText: String,
    options: List<AppOption<T>>,
    selected: T?,
    enabled: Boolean = true,
    id: String = title,
    open: Boolean? = null,
    onOpenChange: ((Boolean) -> Unit)? = null,
    icon: RoseIcon? = null,
    pending: T? = null,
    onSelect: (T) -> Unit,
) {
    val shared = LocalChoiceDialog.current
    val local = remember { mutableStateOf<String?>(null) }
    val owner = shared ?: local
    val isOpen = open ?: (owner.value == id)
    fun setOpen(value: Boolean) {
        if (onOpenChange != null) onOpenChange(value) else owner.value = if (value) id else null
    }
    val select: (T) -> Unit = { value ->
        if (enabled) {
            setOpen(false)
            if (value != selected) onSelect(value)
        }
    }
    val pendingLabel = pending?.let { requested -> options.firstOrNull { it.value == requested }?.label }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixChoiceRow(title, valueText, options, selected, enabled, icon, isOpen, ::setOpen, pendingLabel, select)

        InterfaceStyle.MATERIAL3 ->
            MaterialChoiceRow(title, valueText, options, selected, enabled, icon, isOpen, ::setOpen, pendingLabel, select)
    }
}

@Composable
private fun <T> MaterialChoiceRow(
    title: String,
    valueText: String,
    options: List<AppOption<T>>,
    selected: T?,
    enabled: Boolean,
    icon: RoseIcon?,
    isOpen: Boolean,
    setOpen: (Boolean) -> Unit,
    pendingLabel: String?,
    select: (T) -> Unit,
) {
    ListItem(
        onClick = { setOpen(true) },
        modifier = Modifier.semantics { role = Role.Button },
        enabled = enabled,
        leadingContent = iconAction(icon),
        trailingContent = { AppIcon(RoseIcon.CHEVRON) },
        verticalAlignment = RowAlignment,
        supportingContent = { ChoiceValue(valueText, pendingLabel) },
        colors = rowColors(),
    ) { Text(title) }
    if (isOpen) {
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { setOpen(false) },
            modifier = Modifier.focusRequester(focusRequester).closeOnEscape { setOpen(false) }.focusable(),
            title = { Text(title) },
            text = {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                CompositionLocalProvider(LocalRowPlacement provides RowPlacement.Contained) {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        AppRadioGroup(options, selected, enabled, onSelect = select)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { setOpen(false) }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChoiceValue(valueText: String, pendingLabel: String?) {
    if (pendingLabel == null) {
        Text(valueText)
    } else {
        Column {
            Text(valueText)
            PendingChoiceLine(pendingLabel)
        }
    }
}

internal fun Modifier.closeOnEscape(close: () -> Unit): Modifier = onPreviewKeyEvent {
    val escape = it.type == KeyEventType.KeyUp && it.key == Key.Escape
    if (escape) close()
    escape
}

@Composable
private fun PendingChoiceLine(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppBusyIndicator(16.dp, "正在设为 $label")
        AppText("正在设为 $label", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, role = AppTextRole.Summary)
    }
}

@Composable
internal fun <T> AppMultiChoiceRow(
    title: String,
    summary: String,
    options: List<AppOption<T>>,
    checked: Set<T>,
    enabled: Boolean,
    enabledFor: (T) -> Boolean,
    onToggle: (T) -> Unit,
) {
    val local = remember { mutableStateOf<String?>(null) }
    val owner = LocalChoiceDialog.current ?: local
    val isOpen = owner.value == title
    fun setOpen(open: Boolean) {
        owner.value = if (open) title else null
    }
    val canToggle: (T) -> Boolean = { enabled && enabledFor(it) }
    val toggle: (T) -> Unit = { if (canToggle(it)) onToggle(it) }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            MiuixChoicePopup(isOpen, ::setOpen, dropdown = { expanded ->
                val entry = remember(options, checked, enabled, enabledFor, onToggle) {
                    DropdownEntry(
                        options.map { option ->
                            DropdownItem(
                                text = option.label,
                                selected = option.value in checked,
                                enabled = canToggle(option.value),
                                summary = option.disabledReason,
                                onClick = { toggle(option.value) },
                            )
                        },
                    )
                }
                OverlayDropdownPreference(
                    entry = entry,
                    title = title,
                    summary = summary,
                    showValue = false,
                    enabled = enabled,
                    collapseOnSelection = false,
                    onExpandedChange = expanded,
                )
            }) {
                ArrowPreference(title, summary = summary, enabled = enabled, onClick = { setOpen(true) })
                OverlayDialog(show = true, title = title, onDismissRequest = { setOpen(false) }) {
                    AppCheckGroup(options, checked, canToggle, toggle)
                    AppButton("关闭", { setOpen(false) }, kind = AppButtonKind.Secondary)
                }
            }
        }

        InterfaceStyle.MATERIAL3 -> {
            ListItem(
                onClick = { setOpen(true) },
                modifier = Modifier.semantics { role = Role.Button },
                enabled = enabled,
                trailingContent = { AppIcon(RoseIcon.CHEVRON) },
                verticalAlignment = RowAlignment,
                supportingContent = { Text(summary) },
                colors = rowColors(),
            ) { Text(title) }
            if (isOpen) {
                val focusRequester = remember { FocusRequester() }
                AlertDialog(
                    onDismissRequest = { setOpen(false) },
                    modifier = Modifier.focusRequester(focusRequester).closeOnEscape { setOpen(false) }.focusable(),
                    title = { Text(title) },
                    text = {
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        CompositionLocalProvider(LocalRowPlacement provides RowPlacement.Contained) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                AppCheckGroup(options, checked, canToggle, toggle)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { setOpen(false) }) { Text("关闭") } },
                )
            }
        }
    }
}

@Composable
internal fun <T> AppSegmented(
    options: List<AppOption<T>>,
    selected: T?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    onSelect: (T) -> Unit,
) {
    if (options.isEmpty()) return
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                MiuixTextButton(
                    option.label,
                    onClick = { if (option.value != selected) onSelect(option.value) },
                    enabled = enabled,
                    insideMargin = contentPadding ?: MiuixButtonDefaults.InsideMargin,
                    modifier = Modifier.weight(1f).semantics {
                        this.selected = option.value == selected
                        role = Role.RadioButton
                        option.contentDescription?.let { contentDescription = it }
                    },
                    colors = if (option.value == selected) {
                        MiuixButtonDefaults.textButtonColorsPrimary()
                    } else {
                        MiuixButtonDefaults.textButtonColors()
                    },
                )
            }
        }

        InterfaceStyle.MATERIAL3 -> MaterialSegmented(options, selected, enabled, modifier, contentPadding, onSelect)
    }
}

@Composable
private fun <T> MaterialSegmented(
    options: List<AppOption<T>>,
    selected: T?,
    enabled: Boolean,
    modifier: Modifier,
    contentPadding: PaddingValues?,
    onSelect: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, option ->
            val chosen = selected != null && option.value == selected
            val shapes = connectedSegmentShapes(i, options.size)
            ToggleButton(
                checked = chosen,
                onCheckedChange = { if (it && option.value != selected) onSelect(option.value) },
                modifier = Modifier.weight(1f).semantics {
                    role = Role.RadioButton
                    this.selected = chosen
                    option.contentDescription?.let { contentDescription = it }
                },
                enabled = enabled,
                shapes = shapes,
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurfaceVariant,
                    checkedContainerColor = scheme.secondaryContainer,
                    checkedContentColor = scheme.onSecondaryContainer,
                    disabledContainerColor = if (chosen) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                    disabledContentColor = if (chosen) {
                        scheme.onSecondaryContainer.copy(alpha = 0.72f)
                    } else {
                        scheme.onSurface.copy(alpha = 0.38f)
                    },
                ),
                contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            ) { Text(option.label) }
        }
    }
}

@Composable
private fun connectedSegmentShapes(index: Int, count: Int) = when {
    count == 1 -> ToggleButtonDefaults.shapes(checkedShape = ButtonGroupDefaults.connectedButtonCheckedShape)
    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

@Composable
internal fun AppViewTabs(tabs: List<String>, index: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> TabRowWithContour(tabs, index, onSelect, modifier = modifier)

        InterfaceStyle.MATERIAL3 -> if (tabs.isNotEmpty()) {
            SecondaryTabRow(selectedTabIndex = index, modifier = modifier) {
                tabs.forEachIndexed { tab, label ->
                    Tab(selected = tab == index, onClick = { if (tab != index) onSelect(tab) }, text = { Text(label) })
                }
            }
        }
    }
}

@Composable
fun <T> AppCheckGroup(options: List<AppOption<T>>, checked: Set<T>, enabledFor: (T) -> Boolean, onToggle: (T) -> Unit) {
    Column(verticalArrangement = rowArrangement()) {
        options.forEach { option ->
            val enabled = enabledFor(option.value) && option.disabledReason == null
            val optionModifier = Modifier.semantics { option.contentDescription?.let { contentDescription = it } }
            when (LocalInterfaceStyle.current) {
                InterfaceStyle.MIUIX -> CheckboxPreference(
                    option.label,
                    option.value in checked,
                    { onToggle(option.value) },
                    modifier = optionModifier,
                    enabled = enabled,
                    summary = option.disabledReason,
                )

                InterfaceStyle.MATERIAL3 -> ListItem(
                    checked = option.value in checked,
                    onCheckedChange = { onToggle(option.value) },
                    modifier = optionModifier,
                    enabled = enabled,
                    leadingContent = { Checkbox(option.value in checked, onCheckedChange = null, enabled = enabled) },
                    verticalAlignment = RowAlignment,
                    supportingContent = option.disabledReason?.let { { Text(it) } },
                    colors = rowColors(),
                ) { Text(option.label) }
            }
        }
    }
}

@Composable
internal fun AppSyncIndicator(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp)) {
        if (!active) return@Box
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
            )

            InterfaceStyle.MATERIAL3 -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    kind: AppButtonKind = AppButtonKind.Primary,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (kind == AppButtonKind.Primary) {
            MiuixTextButton(text, onClick, modifier = modifier, enabled = enabled, colors = MiuixButtonDefaults.textButtonColorsPrimary())
        } else {
            MiuixTextButton(text, onClick, modifier = modifier, enabled = enabled)
        }

        InterfaceStyle.MATERIAL3 -> if (kind == AppButtonKind.Primary) {
            Button(onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled) { Text(text) }
        } else {
            OutlinedButton(onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, enabled = enabled) { Text(text) }
        }
    }
}

@Composable
fun Tagged(tag: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().testTag(tag)) { content() }
}

@Composable
internal fun AppProgress(fraction: Float?) {
    if (fraction == null) return
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (fraction <= .2f) {
            top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
                progress = fraction,
                colors = top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = appColor(AppColorRole.Error),
                ),
            )
        } else {
            top.yukonga.miuix.kmp.basic.LinearProgressIndicator(progress = fraction)
        }

        InterfaceStyle.MATERIAL3 -> if (fraction <= .2f) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = appColor(AppColorRole.Error))
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AppBusyIndicator(size: androidx.compose.ui.unit.Dp? = null, contentDescription: String = "正在处理") {
    val described = Modifier.semantics { this.contentDescription = contentDescription }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (size == null) {
            top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator(described)
        } else {
            top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator(described, size = size)
        }

        InterfaceStyle.MATERIAL3 -> CircularProgressIndicator(described.size(size ?: 24.dp))
    }
}

@Composable
internal fun SettingPageBanner(state: io.github.dohex.openrose.core.ControlState, device: io.github.dohex.openrose.core.DeviceState) {
    if (state.isReady(device)) return
    val message = "控制会话尚未就绪，暂不能设置。"
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> AppGroup { BasicComponent(title = message) }

        InterfaceStyle.MATERIAL3 -> Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(message, Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun miuixColor(role: AppColorRole): Color = with(MiuixTheme.colorScheme) {
    when (role) {
        AppColorRole.Primary -> primary
        AppColorRole.OnSurface -> onSurface
        AppColorRole.Summary -> onSurfaceVariantSummary
        AppColorRole.Error -> error
        AppColorRole.Divider -> dividerLine
        AppColorRole.SelectedContainer -> tertiaryContainer
        AppColorRole.SelectedContent -> onTertiaryContainer
        AppColorRole.DisabledContent -> disabledOnSurface
    }
}

@Composable
private fun materialColor(role: AppColorRole): Color = with(MaterialTheme.colorScheme) {
    when (role) {
        AppColorRole.Primary -> primary
        AppColorRole.OnSurface -> onSurface
        AppColorRole.Summary -> onSurfaceVariant
        AppColorRole.Error -> error
        AppColorRole.Divider -> outlineVariant
        AppColorRole.SelectedContainer -> secondaryContainer
        AppColorRole.SelectedContent -> onSecondaryContainer
        AppColorRole.DisabledContent -> onSurface.copy(alpha = 0.38f)
    }
}

@Composable
private fun miuixTextStyle(role: AppTextRole) = with(MiuixTheme.textStyles) {
    when (role) {
        AppTextRole.Display -> title1
        AppTextRole.Title -> title4
        AppTextRole.Body -> main
        AppTextRole.Summary -> body2
        AppTextRole.Footnote -> footnote1
    }
}

@Composable
private fun materialTextStyle(role: AppTextRole) = with(MaterialTheme.typography) {
    when (role) {
        AppTextRole.Display -> displaySmall
        AppTextRole.Title -> titleMedium
        AppTextRole.Body -> bodyLarge
        AppTextRole.Summary -> bodyMedium
        AppTextRole.Footnote -> bodySmall
    }
}

@Composable
private fun <T> MiuixChoiceRow(
    title: String,
    valueText: String,
    options: List<AppOption<T>>,
    selected: T?,
    enabled: Boolean,
    icon: RoseIcon?,
    isOpen: Boolean,
    setOpen: (Boolean) -> Unit,
    pendingLabel: String?,
    select: (T) -> Unit,
) {
    val selectedIndex = options.indexOfFirst { it.value == selected }
    val dropdownFits = selected != null && selectedIndex >= 0 && options.none { it.disabledReason != null }
    MiuixChoicePopup(isOpen, setOpen, dropdownFits, dropdown = { expanded ->
        OverlayDropdownPreference(
            items = options.map { it.label },
            selectedIndex = selectedIndex,
            title = title,
            enabled = enabled,
            startAction = iconAction(icon),
            bottomAction = pendingLabel?.let { { PendingChoiceLine(it) } },
            onExpandedChange = expanded,
            onSelectedIndexChange = { select(options[it].value) },
        )
    }) {
        ArrowPreference(
            title,
            summary = valueText,
            enabled = enabled,
            startAction = iconAction(icon),
            bottomAction = pendingLabel?.let { { PendingChoiceLine(it) } },
            onClick = { setOpen(true) },
        )
        OverlayDialog(show = isOpen, title = title, onDismissRequest = { setOpen(false) }) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                AppRadioGroup(options, selected, enabled, onSelect = select)
            }
            AppButton("取消", { setOpen(false) }, kind = AppButtonKind.Secondary)
        }
    }
}

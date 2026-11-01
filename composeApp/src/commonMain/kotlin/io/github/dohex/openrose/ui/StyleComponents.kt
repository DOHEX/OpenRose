@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.dohex.openrose.core.InterfaceStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

internal val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.MIUIX }
// Owned above the style branch so an open picker survives component replacement.
internal val LocalChoiceDialog = staticCompositionLocalOf<MutableState<String?>?> { null }
private val LocalInDashboardCard = staticCompositionLocalOf { false }
internal enum class AppColorRole { Primary, OnSurface, Summary, Error, Divider, SelectedContainer, SelectedContent, DisabledContent }
internal enum class AppTextRole { Display, Title, Body, Summary, Footnote }
internal enum class AppButtonKind { Primary, Secondary }
internal data class AppOption<T>(val value: T, val label: String, val contentDescription: String? = null)

@Composable
internal fun appColor(role: AppColorRole): Color = when (LocalInterfaceStyle.current) {
    InterfaceStyle.MIUIX -> with(MiuixTheme.colorScheme) {
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
    InterfaceStyle.MATERIAL3 -> with(MaterialTheme.colorScheme) {
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
}

@Composable
internal fun ProvideInterfaceStyle(style: InterfaceStyle, dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInterfaceStyle provides style) {
        when (style) {
            InterfaceStyle.MIUIX -> MiuixTheme(colors = miuixColors(dark), content = content)
            InterfaceStyle.MATERIAL3 -> MaterialTheme(colorScheme = materialColors(dark), content = content)
        }
    }
}

/** Matches platform window decorations to the application's canvas. */
fun interfaceBackgroundColor(style: InterfaceStyle, dark: Boolean): Color = when (style) {
    InterfaceStyle.MIUIX -> miuixColors(dark).surface
    InterfaceStyle.MATERIAL3 -> materialColors(dark).background
}

private fun miuixColors(dark: Boolean) = if (dark) miuixDarkColorScheme() else miuixLightColorScheme()
private fun materialColors(dark: Boolean) = if (dark) {
    darkColorScheme(
        surfaceContainerLowest = Color(0xFF0F0D13),
        surfaceContainerLow = Color(0xFF1D1B20),
        surfaceContainer = Color(0xFF211F26),
        surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerHighest = Color(0xFF36343B),
    )
} else {
    lightColorScheme(
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF3EDF7),
        surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerHighest = Color(0xFFE6E0E9),
    )
}

@Composable
internal fun AppText(text: String, modifier: Modifier = Modifier, role: AppTextRole = AppTextRole.Body, color: Color? = null) {
    val textModifier = if (role == AppTextRole.Display || role == AppTextRole.Title) modifier.semantics { heading() } else modifier
    val textColor = color ?: appColor(if (role == AppTextRole.Summary || role == AppTextRole.Footnote) AppColorRole.Summary else AppColorRole.OnSurface)
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixText(text, modifier = textModifier, color = textColor, style = with(MiuixTheme.textStyles) {
            when (role) {
                AppTextRole.Display -> title1
                AppTextRole.Title -> title4
                AppTextRole.Body -> main
                AppTextRole.Summary -> body2
                AppTextRole.Footnote -> footnote1
            }
        })
        InterfaceStyle.MATERIAL3 -> Text(text, modifier = textModifier, color = textColor, style = with(MaterialTheme.typography) {
            when (role) {
                AppTextRole.Display -> displaySmall
                AppTextRole.Title -> titleMedium
                AppTextRole.Body -> bodyLarge
                AppTextRole.Summary -> bodyMedium
                AppTextRole.Footnote -> bodySmall
            }
        })
    }
}

@Composable
internal fun AppGroup(
    title: String? = null,
    header: (@Composable () -> Unit)? = null,
    footnote: String? = null,
    footnoteIcon: RoseIcon? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    plain: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> {
                title?.let { label ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SmallTitle(label, Modifier.weight(1f).semantics { heading() })
                        titleTrailing?.let { Box(Modifier.padding(end = 16.dp)) { it() } }
                    }
                }
                header?.let {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                        it()
                    }
                }
                if (plain) Column(Modifier.fillMaxWidth().padding(start = 28.dp), content = content)
                else MiuixCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp), content = content)
            }
            InterfaceStyle.MATERIAL3 -> {
                title?.let { label ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f).semantics { heading() }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                        titleTrailing?.invoke()
                    }
                }
                header?.let {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                        it()
                    }
                }
                Column(Modifier.fillMaxWidth().padding(start = if (plain) 16.dp else 0.dp), content = content)
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
        if (LocalInterfaceStyle.current == InterfaceStyle.MATERIAL3) {
            Spacer(Modifier.height(12.dp))
            AppDivider()
        }
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
    CompositionLocalProvider(LocalInDashboardCard provides true) {
        when (LocalInterfaceStyle.current) {
            InterfaceStyle.MIUIX -> MiuixCard(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            }
            InterfaceStyle.MATERIAL3 -> ElevatedCard(
                modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            }
        }
    }
}

@Composable
private fun rowColors(): ListItemColors = if (LocalInDashboardCard.current) ListItemDefaults.colors(containerColor = Color.Transparent) else ListItemDefaults.colors()

private fun iconAction(icon: RoseIcon?): (@Composable () -> Unit)? = icon?.let { { AppIcon(it) } }

@Composable
internal fun AppArrowRow(title: String, summary: String? = null, value: String? = null, icon: RoseIcon? = null, onClick: () -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> ArrowPreference(title, summary = summary, startAction = iconAction(icon), endActions = { value?.let { AppText(it, role = AppTextRole.Summary) } }, onClick = onClick)
        InterfaceStyle.MATERIAL3 -> ListItem(
            headlineContent = { Text(title) }, supportingContent = summary?.let { { Text(it) } },
            leadingContent = iconAction(icon), trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { value?.let { Text(it) }; AppIcon(RoseIcon.CHEVRON) } },
            modifier = Modifier.clickable(role = Role.Button, onClick = onClick), colors = rowColors(),
        )
    }
}

@Composable
internal fun AppInfoRow(label: String, value: String, valueColor: Color? = null) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (valueColor != null) BasicComponent(title = label, bottomAction = { AppText(value, role = AppTextRole.Summary, color = valueColor) })
            else if (value.length > 32 || '\n' in value) BasicComponent(title = label, summary = value)
            else BasicComponent(title = label, endActions = { AppText(value, role = AppTextRole.Summary) })
        InterfaceStyle.MATERIAL3 -> ListItem(headlineContent = { Text(label) }, supportingContent = { Text(value, color = valueColor ?: Color.Unspecified) }, colors = rowColors())
    }
}

@Composable
internal fun AppSwitchRow(
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
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = iconAction(icon),
            trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
            modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
            colors = rowColors(),
        )
    }
}

@Composable
internal fun <T> AppRadioGroup(options: List<AppOption<T>>, selected: T?, enabled: Boolean = true, transparentContainer: Boolean = false, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            val optionModifier = Modifier.semantics {
                this.selected = selected != null && option.value == selected
                option.contentDescription?.let { contentDescription = it }
                if (!enabled) disabled()
            }
            when (LocalInterfaceStyle.current) {
                InterfaceStyle.MIUIX -> RadioButtonPreference(option.label, selected != null && option.value == selected,
                    onClick = { if (enabled) onSelect(option.value) }, modifier = optionModifier, enabled = enabled)
                InterfaceStyle.MATERIAL3 -> ListItem(headlineContent = { Text(option.label) },
                    leadingContent = { RadioButton(selected != null && option.value == selected, onClick = null, enabled = enabled) },
                    modifier = optionModifier.selectable(selected != null && option.value == selected, enabled, Role.RadioButton) { onSelect(option.value) },
                    colors = if (transparentContainer) ListItemDefaults.colors(containerColor = Color.Transparent) else rowColors())
            }
        }
    }
}

@Composable
internal fun <T> AppChoiceRow(title: String, valueText: String, options: List<AppOption<T>>, selected: T?, enabled: Boolean = true,
    id: String = title, open: Boolean? = null, onOpenChange: ((Boolean) -> Unit)? = null, icon: RoseIcon? = null, onSelect: (T) -> Unit) {
    val shared = LocalChoiceDialog.current
    val local = remember { mutableStateOf<String?>(null) }
    val owner = shared ?: local
    val isOpen = open ?: (owner.value == id)
    fun setOpen(value: Boolean) { if (onOpenChange != null) onOpenChange(value) else owner.value = if (value) id else null }
    val select: (T) -> Unit = { value ->
        if (enabled) {
            setOpen(false)
            if (value != selected) onSelect(value)
        }
    }
    // 0.9.4 exposes expansion notifications, but no controlled expanded parameter.
    // A picker restored into a new Miuix shell uses its controlled dialog equivalent.
    var dropdownOpenedHere by remember { mutableStateOf(false) }
    var dropdownGeneration by remember { mutableIntStateOf(0) }
    val dialogFocusRequester = remember { FocusRequester() }
    val backdropActive = LocalBackdropBlurActive.current
    val backdrop = LocalAppBackdrop.current
    LaunchedEffect(isOpen) {
        if (!isOpen && dropdownOpenedHere) {
            dropdownOpenedHere = false
            dropdownGeneration++
        }
    }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            val selectedIndex = options.indexOfFirst { it.value == selected }
            if (selected != null && selectedIndex >= 0 && (!isOpen || dropdownOpenedHere)) {
                key(dropdownGeneration) {
                    OverlayDropdownPreference(items = options.map { it.label }, selectedIndex = selectedIndex, title = title,
                        enabled = enabled, startAction = iconAction(icon),
                        onExpandedChange = { dropdownOpenedHere = it; setOpen(it) },
                        onSelectedIndexChange = { select(options[it].value) })
                }
            } else {
                ArrowPreference(title, summary = valueText, enabled = enabled, startAction = iconAction(icon), onClick = { setOpen(true) })
                OverlayDialog(show = isOpen, title = title, onDismissRequest = { setOpen(false) }) {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        AppRadioGroup(options, selected, enabled, onSelect = select)
                    }
                    AppButton("取消", { setOpen(false) }, kind = AppButtonKind.Secondary)
                }
            }
        }
        InterfaceStyle.MATERIAL3 -> {
            ListItem(headlineContent = { Text(title) }, supportingContent = { Text(valueText) },
                leadingContent = iconAction(icon),
                modifier = Modifier.clickable(enabled, role = Role.Button) { setOpen(true) }, colors = rowColors())
            if (isOpen) AlertDialog(
                onDismissRequest = { setOpen(false) },
                modifier = Modifier
                    .focusRequester(dialogFocusRequester)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) {
                            setOpen(false)
                            true
                        } else false
                    }
                    .focusable(),
                title = { Text(title) },
                text = {
                    LaunchedEffect(Unit) { dialogFocusRequester.requestFocus() }
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { AppRadioGroup(options, selected, enabled, transparentContainer = true, onSelect = select) }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { setOpen(false) }) { Text("取消") } },
            )
        }
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
    fun setOpen(open: Boolean) { owner.value = if (open) title else null }
    val canToggle: (T) -> Boolean = { enabled && enabledFor(it) }
    val toggle: (T) -> Unit = { if (canToggle(it)) onToggle(it) }
    // Like AppChoiceRow, restore an open picker as a controlled dialog after a style change.
    var dropdownOpenedHere by remember { mutableStateOf(false) }
    var dropdownGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(isOpen) {
        if (!isOpen && dropdownOpenedHere) {
            dropdownOpenedHere = false
            dropdownGeneration++
        }
    }
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> {
            if (!isOpen || dropdownOpenedHere) {
                val entry = remember(options, checked, enabled, enabledFor, onToggle) {
                    DropdownEntry(options.map { option ->
                        DropdownItem(
                            text = option.label,
                            selected = option.value in checked,
                            enabled = canToggle(option.value),
                            onClick = { toggle(option.value) },
                        )
                    })
                }
                key(dropdownGeneration) {
                    OverlayDropdownPreference(
                        entry = entry,
                        title = title,
                        summary = summary,
                        showValue = false,
                        enabled = enabled,
                        collapseOnSelection = false,
                        onExpandedChange = { dropdownOpenedHere = it; setOpen(it) },
                    )
                }
            } else {
                ArrowPreference(title, summary = summary, enabled = enabled, onClick = { setOpen(true) })
                OverlayDialog(show = true, title = title, onDismissRequest = { setOpen(false) }) {
                    AppCheckGroup(options, checked, canToggle, toggle)
                    AppButton("关闭", { setOpen(false) }, kind = AppButtonKind.Secondary)
                }
            }
        }
        InterfaceStyle.MATERIAL3 -> {
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(summary) },
                trailingContent = { AppIcon(RoseIcon.CHEVRON) },
                modifier = Modifier.clickable(enabled, role = Role.Button) { setOpen(true) },
                colors = rowColors(),
            )
            if (isOpen) {
                val focusRequester = remember { FocusRequester() }
                AlertDialog(
                    onDismissRequest = { setOpen(false) },
                    modifier = Modifier.focusRequester(focusRequester).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) {
                            setOpen(false)
                            true
                        } else false
                    }.focusable(),
                    title = { Text(title) },
                    text = {
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        AppCheckGroup(options, checked, canToggle, toggle)
                    },
                    confirmButton = { TextButton(onClick = { setOpen(false) }) { Text("关闭") } },
                )
            }
        }
    }
}

@Composable
internal fun <T> AppSegmented(options: List<AppOption<T>>, selected: T?, enabled: Boolean = true, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    if (options.isEmpty()) return
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                MiuixTextButton(option.label, onClick = { if (option.value != selected) onSelect(option.value) }, enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { this.selected = option.value == selected; role = Role.RadioButton; option.contentDescription?.let { contentDescription = it } },
                    colors = if (option.value == selected) MiuixButtonDefaults.textButtonColorsPrimary() else MiuixButtonDefaults.textButtonColors())
            }
        }
        InterfaceStyle.MATERIAL3 -> SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(selected = selected != null && option.value == selected, onClick = { if (option.value != selected) onSelect(option.value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size), enabled = enabled,
                    modifier = Modifier.semantics { option.contentDescription?.let { contentDescription = it } }) { Text(option.label) }
            }
        }
    }
}

@Composable
internal fun AppViewTabs(tabs: List<String>, index: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> TabRowWithContour(tabs, index, onSelect, modifier = modifier)
        InterfaceStyle.MATERIAL3 -> AppSegmented(tabs.mapIndexed { i, label -> AppOption(i, label) }, index, modifier = modifier, onSelect = onSelect)
    }
}

@Composable
internal fun <T> AppCheckGroup(options: List<AppOption<T>>, checked: Set<T>, enabledFor: (T) -> Boolean, onToggle: (T) -> Unit) {
    Column {
        options.forEach { option ->
            val enabled = enabledFor(option.value)
            val optionModifier = Modifier.semantics { option.contentDescription?.let { contentDescription = it } }
            when (LocalInterfaceStyle.current) {
                InterfaceStyle.MIUIX -> CheckboxPreference(option.label, option.value in checked, { onToggle(option.value) }, modifier = optionModifier, enabled = enabled)
                InterfaceStyle.MATERIAL3 -> ListItem(headlineContent = { Text(option.label) }, leadingContent = { Checkbox(option.value in checked, onCheckedChange = null, enabled = enabled) },
                    modifier = optionModifier.toggleable(option.value in checked, enabled, Role.Checkbox) { onToggle(option.value) }, colors = rowColors())
            }
        }
    }
}

@Composable
internal fun AppSliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, onChange: (Float) -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> SliderPreference(value, onChange, modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label 草稿增益"
            progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
            if (!enabled) disabled()
            setProgress { requested ->
                if (enabled) onChange(requested.coerceIn(range))
                enabled
            }
        }, title = label, valueText = valueText, valueRange = range, steps = steps, enabled = enabled)
        InterfaceStyle.MATERIAL3 -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(valueText) }
            Slider(value, onChange, valueRange = range, steps = steps, enabled = enabled, modifier = Modifier.semantics { contentDescription = "$label 草稿增益" })
        }
    }
}

@Composable
internal fun AppButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, kind: AppButtonKind = AppButtonKind.Primary) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (kind == AppButtonKind.Primary) MiuixTextButton(text, onClick, modifier = modifier, enabled = enabled, colors = MiuixButtonDefaults.textButtonColorsPrimary())
            else MiuixTextButton(text, onClick, modifier = modifier, enabled = enabled)
        InterfaceStyle.MATERIAL3 -> if (kind == AppButtonKind.Primary) Button(onClick, modifier, enabled, shape = RoundedCornerShape(16.dp)) { Text(text) }
            else OutlinedButton(onClick, modifier, enabled, shape = RoundedCornerShape(16.dp)) { Text(text) }
    }
}

@Composable
internal fun AppProgress(fraction: Float?) {
    if (fraction == null) return
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (fraction <= .2f) top.yukonga.miuix.kmp.basic.LinearProgressIndicator(progress = fraction,
            colors = top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = appColor(AppColorRole.Error)))
            else top.yukonga.miuix.kmp.basic.LinearProgressIndicator(progress = fraction)
        InterfaceStyle.MATERIAL3 -> if (fraction <= .2f) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = appColor(AppColorRole.Error))
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun AppBusyIndicator() {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator()
        InterfaceStyle.MATERIAL3 -> CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
internal fun AppDivider() {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> top.yukonga.miuix.kmp.basic.HorizontalDivider()
        InterfaceStyle.MATERIAL3 -> HorizontalDivider()
    }
}

@Composable
internal fun AppCandidateRow(title: String, summary: String, onConnect: (() -> Unit)?, enabled: Boolean = true) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(title = title, summary = summary, endActions = { onConnect?.let { MiuixTextButton("连接", it, enabled = enabled) } })
        InterfaceStyle.MATERIAL3 -> ListItem(headlineContent = { Text(title) }, supportingContent = { Text(summary) }, trailingContent = onConnect?.let { { FilledTonalButton(it, enabled = enabled) { Text("连接") } } })
    }
}

@Composable
internal fun SettingPageBanner(state: io.github.dohex.openrose.core.ControlState, device: io.github.dohex.openrose.core.DeviceState) {
    if (state.isReady(device)) return
    val message = "控制会话尚未就绪，暂不能设置。"
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> AppGroup { BasicComponent(title = message) }
        InterfaceStyle.MATERIAL3 -> Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(message, Modifier.padding(16.dp))
        }
    }
}

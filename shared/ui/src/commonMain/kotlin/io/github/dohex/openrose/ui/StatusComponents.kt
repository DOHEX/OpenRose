package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** The theme facts status surfaces pick their tints from; Miuix reports neither for an explicit colour scheme. */
internal data class ThemeTone(val dark: Boolean, val dynamicColor: Boolean)

internal val LocalThemeTone = staticCompositionLocalOf { ThemeTone(dark = false, dynamicColor = false) }

enum class AppStatusKind { Good, Caution, Error }

/** A fix the status hero offers for what it reports, such as granting the Bluetooth permission. */
class AppStatusAction(val label: String, val onClick: () -> Unit)

/** Whether OpenRose can control earphones now, as the status hero states it. */
data class StatusVerdict(
    val kind: AppStatusKind,
    val title: String,
    val summary: String?,
    val actions: List<AppStatusAction> = emptyList(),
)

/** A status surface's fill, its body text, and the accent for its emblem and emphasis. */
private class StatusColors(val container: Color, val content: Color, val accent: Color)

/** KernelSU's fixed Miuix fills for a light and a dark theme, and the accent both share. */
private class FixedTint(val light: Color, val dark: Color, val accent: Color)

private fun fixedTint(kind: AppStatusKind): FixedTint = when (kind) {
    AppStatusKind.Good -> FixedTint(Color(0xFFDFFAE4), Color(0xFF1A3825), Color(0xFF36D167))
    AppStatusKind.Caution -> FixedTint(Color(0xFFFFF0DB), Color(0xFF3E2F1B), Color(0xFFF5A623))
    AppStatusKind.Error -> FixedTint(Color(0xFFF8E2E2), Color(0xFF310808), Color(0xFFF72727))
}

/**
 * KernelSU's status tints: without 动态取色, Miuix uses fixed green, amber and red fills that neither of its schemes
 * provides; with it, and always in Material 3, the scheme's container roles stand in.
 */
@Composable
private fun statusColors(kind: AppStatusKind): StatusColors {
    val tone = LocalThemeTone.current
    return when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> if (tone.dynamicColor) {
            miuixSchemeStatusColors(kind)
        } else {
            val tint = fixedTint(kind)
            StatusColors(if (tone.dark) tint.dark else tint.light, MiuixTheme.colorScheme.onSurfaceContainer, tint.accent)
        }

        InterfaceStyle.MATERIAL3 -> with(MaterialTheme.colorScheme) {
            when (kind) {
                AppStatusKind.Good -> StatusColors(secondaryContainer, onSecondaryContainer, primary)
                AppStatusKind.Caution -> StatusColors(tertiaryContainer, onTertiaryContainer, tertiary)
                AppStatusKind.Error -> StatusColors(errorContainer, onErrorContainer, error)
            }
        }
    }
}

@Composable
private fun miuixSchemeStatusColors(kind: AppStatusKind): StatusColors = with(MiuixTheme.colorScheme) {
    when (kind) {
        AppStatusKind.Good -> StatusColors(secondaryContainer, onSecondaryContainer, primary.copy(alpha = 0.8f))
        AppStatusKind.Caution -> StatusColors(tertiaryContainer, onTertiaryContainer, onTertiaryContainer)
        AppStatusKind.Error -> StatusColors(errorContainer, onErrorContainer, error)
    }
}

/** A card filled with [container], inset like [AppDashboardCard] so it lines up with the cards around it. */
@Composable
private fun StatusSurface(container: Color, content: Color, modifier: Modifier, body: @Composable () -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixCard(
            modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = MiuixCardDefaults.defaultColors(color = container, contentColor = content),
        ) { body() }

        InterfaceStyle.MATERIAL3 -> Card(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.largeIncreased,
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        ) { body() }
    }
}

@Composable
private fun StatusText(
    text: String,
    color: Color,
    miuix: @Composable () -> TextStyle,
    material: @Composable () -> TextStyle,
    modifier: Modifier = Modifier,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixText(text, modifier, color = color, style = miuix())
        InterfaceStyle.MATERIAL3 -> Text(text, modifier, color = color, style = material())
    }
}

/**
 * The status page's verdict, set like KernelSU's status card: the verdict large on a tinted card, one line of detail,
 * the fixes on offer, the host's [badge] in the lower start corner and an emblem bleeding off the lower end corner.
 */
@Composable
fun AppStatusHero(verdict: StatusVerdict, badge: String?, modifier: Modifier = Modifier) {
    val colors = statusColors(verdict.kind)
    StatusSurface(colors.container, colors.content, modifier) {
        Box(Modifier.fillMaxWidth()) {
            AppIcon(
                icon = when (verdict.kind) {
                    AppStatusKind.Good -> RoseIcon.CHECK_CIRCLE
                    AppStatusKind.Caution -> RoseIcon.WARNING
                    AppStatusKind.Error -> RoseIcon.ERROR
                },
                modifier = Modifier.align(Alignment.BottomEnd).offset(27.dp, 31.dp).size(110.dp),
                tint = colors.accent,
            )
            Column(Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp)) {
                // The end inset keeps the text clear of the emblem.
                val textModifier = Modifier.padding(end = 72.dp)
                StatusText(
                    verdict.title,
                    colors.content,
                    miuix = { MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold) },
                    material = { MaterialTheme.typography.titleLarge },
                    modifier = textModifier.semantics { heading() },
                )
                verdict.summary?.let { summary ->
                    StatusText(
                        summary,
                        colors.content.copy(alpha = 0.8f),
                        miuix = { MiuixTheme.textStyles.body1 },
                        material = { MaterialTheme.typography.bodyLarge },
                        modifier = textModifier.padding(top = 2.dp),
                    )
                }
                if (verdict.actions.isNotEmpty()) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        verdict.actions.forEach { AppButton(it.label, it.onClick) }
                    }
                }
                badge?.let {
                    Spacer(Modifier.weight(1f))
                    StatusText(
                        it,
                        colors.content,
                        miuix = { MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium) },
                        material = { MaterialTheme.typography.titleSmall },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

/** A titleless card of status facts or entries, as KernelSU groups the facts under its status card. */
@Composable
fun AppStatusSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppDashboardCard(modifier, compact = true, content = content)
}

/** One status fact, set like KernelSU's info rows: an icon, the fact's name, and its value beneath in [tone]. */
@Composable
fun AppFactRow(icon: RoseIcon, label: String, value: String, tone: StatusTone = StatusTone.NEUTRAL) {
    val valueColor = tone.color()
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> BasicComponent(
            title = label,
            summary = value,
            summaryColor = BasicComponentDefaults.summaryColor(color = valueColor),
            startAction = { AppIcon(icon, Modifier.padding(end = 4.dp), tint = MiuixTheme.colorScheme.onSurface) },
        )

        InterfaceStyle.MATERIAL3 -> ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(value, color = valueColor) },
            leadingContent = { AppIcon(icon) },
            colors = rowColors(),
        )
    }
}

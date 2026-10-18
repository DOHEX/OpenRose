package io.github.dohex.openrose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dohex.openrose.core.InterfaceStyle
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalInterfaceStyle = staticCompositionLocalOf { InterfaceStyle.MIUIX }

@Composable
internal fun pageBackground(): Color = when (LocalInterfaceStyle.current) {
    InterfaceStyle.MIUIX -> MiuixTheme.colorScheme.background
    InterfaceStyle.MATERIAL3 -> MaterialTheme.colorScheme.background
}

@Composable
internal fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    title: Boolean = false,
    secondary: Boolean = false,
    large: Boolean = false,
) {
    val headingModifier = if (title || large) modifier.semantics { heading() } else modifier
    val size = if (large) 32.sp else if (title) 20.sp else 15.sp
    val weight = if (title || large) FontWeight.SemiBold else FontWeight.Normal
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixText(
            text = text,
            modifier = headingModifier,
            fontSize = size,
            fontWeight = weight,
            lineHeight = if (large) 40.sp else 24.sp,
            color = if (secondary) MiuixTheme.colorScheme.onSurfaceVariantSummary
            else MiuixTheme.colorScheme.onBackground,
        )
        InterfaceStyle.MATERIAL3 -> Text(
            text = text,
            modifier = headingModifier,
            fontSize = size,
            fontWeight = weight,
            lineHeight = if (large) 40.sp else 24.sp,
            color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
internal fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixCard(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp),
            content = content,
        )
        InterfaceStyle.MATERIAL3 -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
internal fun AppButton(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    choice: Boolean = false,
    enabled: Boolean = true,
) {
    val modifier = Modifier.heightIn(min = 48.dp).then(
        if (choice) Modifier.semantics { this.selected = selected } else Modifier,
    )
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixTextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (selected || !choice) MiuixButtonDefaults.textButtonColorsPrimary()
            else MiuixButtonDefaults.textButtonColors(),
        )
        InterfaceStyle.MATERIAL3 -> if (selected || !choice) {
            Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
        } else {
            OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
        }
    }
}

@Composable
internal fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    when (LocalInterfaceStyle.current) {
        InterfaceStyle.MIUIX -> MiuixSwitch(checked, onCheckedChange, modifier)
        InterfaceStyle.MATERIAL3 -> Switch(checked, onCheckedChange, modifier)
    }
}

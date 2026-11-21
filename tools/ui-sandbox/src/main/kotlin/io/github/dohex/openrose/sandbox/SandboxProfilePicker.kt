package io.github.dohex.openrose.sandbox

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.ui.AppIcon
import io.github.dohex.openrose.ui.RoseIcon
import io.github.dohex.openrose.ui.configurationLabel

private val ModelProfile.series: String
    get() = when (displayName.substringBefore(' ').substringBefore('-').uppercase()) {
        "EARFEEL", "EARFREE" -> "EarFree / EarFeel"
        "BUDSFEEL", "BUDSFREE" -> "BudsFeel / BudsFree"
        "OPENFEEL", "OPENFUN", "OPENLINK" -> "Open 系列"
        "PATAMON", "TAILMON", "GABUMON", "AGUMON" -> "Luminous"
        "CERAMICS" -> "CERAMICS"
        "AGATE" -> "AGATE"
        "CAMBRIAN" -> "Cambrian"
        "SPORTFEEL" -> "SportFeel"
        "SPEAKFEEL" -> "SpeakFeel"
        "SWIFT" -> "SWIFT"
        else -> "其他型号"
    }

private val profilesBySeries = ModelProfile.entries
    .sortedBy { it.displayName.lowercase() }
    .groupBy { it.series }
    .toSortedMap(String.CASE_INSENSITIVE_ORDER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SandboxProfilePicker(selected: ModelProfile, onSelect: (ModelProfile) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var series by remember { mutableStateOf<String?>(null) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            if (open) series = null
            expanded = open
        },
    ) {
        OutlinedTextField(
            value = selected.configurationLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("型号配置") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        val currentSeries = series
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = key(currentSeries) { rememberScrollState() },
        ) {
            if (currentSeries == null) {
                for (name in profilesBySeries.keys) {
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = { AppIcon(RoseIcon.CHEVRON, tint = LocalContentColor.current) },
                        onClick = { series = name },
                    )
                }
            } else {
                DropdownMenuItem(
                    text = { Text("全部系列") },
                    leadingIcon = { AppIcon(RoseIcon.BACK, tint = LocalContentColor.current) },
                    trailingIcon = { Text(currentSeries, style = MaterialTheme.typography.labelMedium) },
                    onClick = { series = null },
                )
                HorizontalDivider()
                for (profile in profilesBySeries.getValue(currentSeries)) {
                    val active = profile == selected
                    DropdownMenuItem(
                        text = { Text(profile.configurationLabel) },
                        modifier = Modifier.semantics { this.selected = active },
                        trailingIcon = { if (active) AppIcon(RoseIcon.CHECK, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            expanded = false
                            onSelect(profile)
                        },
                    )
                }
            }
        }
    }
}

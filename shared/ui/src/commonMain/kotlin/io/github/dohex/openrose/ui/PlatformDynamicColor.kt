package io.github.dohex.openrose.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal expect fun platformMaterialDynamicColors(dark: Boolean): ColorScheme?

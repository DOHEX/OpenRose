package io.github.dohex.openrose.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// Desktop has no system wallpaper palette.
@Composable
internal actual fun platformMaterialDynamicColors(dark: Boolean): ColorScheme? = null

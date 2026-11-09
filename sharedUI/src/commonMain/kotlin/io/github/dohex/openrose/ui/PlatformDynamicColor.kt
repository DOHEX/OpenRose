package io.github.dohex.openrose.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** The Material color scheme of the system wallpaper for 动态取色, or null where the platform provides none. */
@Composable
internal expect fun platformMaterialDynamicColors(dark: Boolean): ColorScheme?

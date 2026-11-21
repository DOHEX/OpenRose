package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable

// Desktop Escape is handled by the shared navigation container.
@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable

@Composable
internal expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

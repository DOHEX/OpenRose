package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable

/** The desktop has no setting that Compose can read cheaply and uniformly across Linux and Windows. */
@Composable
internal actual fun rememberReduceMotion(): Boolean = false

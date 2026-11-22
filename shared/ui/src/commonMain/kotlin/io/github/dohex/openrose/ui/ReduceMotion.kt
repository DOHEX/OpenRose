package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable

/**
 * The user asked the system for no animation. Decorative loops draw a still frame and surface motion
 * (the 快捷面板 sliding in and out) jumps to its end state.
 */
@Composable
internal expect fun rememberReduceMotion(): Boolean

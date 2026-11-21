package io.github.dohex.openrose.core

/** Preferences that affect control transport selection on every platform. */
data class UserSettings(
    val forceBle: Boolean = false,
)

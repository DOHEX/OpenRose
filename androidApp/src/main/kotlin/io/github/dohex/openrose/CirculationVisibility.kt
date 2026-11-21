package io.github.dohex.openrose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import io.github.dohex.openrose.ui.LocalCirculationVisible

@Composable
internal fun ComponentActivity.CirculationVisibility(content: @Composable () -> Unit) {
    val state by lifecycle.currentStateFlow.collectAsState()
    CompositionLocalProvider(LocalCirculationVisible provides state.isAtLeast(Lifecycle.State.STARTED), content = content)
}

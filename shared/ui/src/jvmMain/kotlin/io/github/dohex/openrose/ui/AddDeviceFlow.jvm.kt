package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.dohex.openrose.ui.model.OpenRoseModel

@Composable
internal actual fun rememberAddDeviceFlow(model: OpenRoseModel): AddDeviceFlow {
    val scope = rememberCoroutineScope()
    return remember(model, scope) { AddDeviceFlow(scope, model) }
}

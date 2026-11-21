package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dohex.openrose.ui.model.OpenRoseModel

private class AddDeviceViewModel(model: OpenRoseModel) : ViewModel() {
    val flow = AddDeviceFlow(viewModelScope, model)
}

@Composable
internal actual fun rememberAddDeviceFlow(model: OpenRoseModel): AddDeviceFlow {
    val owner = viewModel { AddDeviceViewModel(model) }
    return owner.flow
}

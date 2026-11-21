package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId

internal class OpenRoseAppHarness(
    scene: OpenRoseUiTestScene,
    val state: MutableState<ControlState>,
    val uiSettings: MutableState<UiSettings>,
    val model: FakeOpenRoseModel,
) : OpenRoseUiTestHarness(scene) {
    val settingsChanges: List<UiSettings> get() = model.settingsChanges
    val controlSettingsChanges: List<UserSettings> get() = model.controlSettingsChanges
    val settingWrites: List<Pair<DeviceSetting, FieldValue>> get() = model.settingWrites
    val connectRequests: List<DeviceId> get() = model.connectRequests
    val disconnectCount: Int get() = model.disconnectCount
}

internal fun withOpenRoseAppHarness(
    initialState: ControlState,
    initialUiSettings: UiSettings = UiSettings(),
    width: Int = 420,
    height: Int = 900,
    block: suspend (OpenRoseAppHarness) -> Unit,
) = runOpenRoseUiTest(width, height) {
    val state = mutableStateOf(initialState)
    val uiSettings = mutableStateOf(initialUiSettings)
    val model = FakeOpenRoseModel(state, uiSettings)
    setContent { OpenRoseApp(model = model, platformUi = BasicPlatformUi("Test")) }
    block(OpenRoseAppHarness(this, state, uiSettings, model))
}

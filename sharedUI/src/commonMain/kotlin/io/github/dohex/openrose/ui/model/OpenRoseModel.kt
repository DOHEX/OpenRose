package io.github.dohex.openrose.ui.model

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Presentation state and intents shared by every page and component style. */
interface OpenRoseModel {
    val state: StateFlow<ControlState>
    val uiSettings: StateFlow<UiSettings>
    val homeNavigationRequest: StateFlow<Long>

    fun updateUiSettings(settings: UiSettings)
    fun updateSettings(settings: UserSettings)
    fun startScan()
    fun stopScan()
    fun connect(deviceId: DeviceId)
    fun disconnect()
    fun set(setting: DeviceSetting, value: FieldValue)
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult
    fun showHeadphones()
}

/** Each host preserves its persistence and control-settings publication rules here. */
interface OpenRoseSettingsStore {
    val uiSettings: StateFlow<UiSettings>
    fun updateUiSettings(settings: UiSettings)
    fun updateSettings(settings: UserSettings)
}

/** Created with the host's control entry and scope, outside navigation and style composition. */
class RuntimeOpenRoseModel(
    private val runtime: ControlRuntime,
    private val settingsStore: OpenRoseSettingsStore,
    private val scope: CoroutineScope,
) : OpenRoseModel {
    override val state: StateFlow<ControlState> = runtime.state
    override val uiSettings: StateFlow<UiSettings> = settingsStore.uiSettings
    private val mutableHomeNavigationRequest = MutableStateFlow(0L)
    override val homeNavigationRequest: StateFlow<Long> = mutableHomeNavigationRequest.asStateFlow()

    override fun updateUiSettings(settings: UiSettings) = settingsStore.updateUiSettings(settings)
    override fun updateSettings(settings: UserSettings) = settingsStore.updateSettings(settings)
    override fun startScan() {
        scope.launch { runtime.startScan() }
    }
    override fun stopScan() {
        scope.launch { runtime.stopScan() }
    }
    override fun connect(deviceId: DeviceId) {
        scope.launch { runtime.connect(deviceId) }
    }
    override fun disconnect() {
        scope.launch { runtime.disconnect() }
    }
    override fun set(setting: DeviceSetting, value: FieldValue) {
        scope.launch { runtime.set(setting, value) }
    }

    // The adding flow owns cancellation of its unfinished connection attempt.
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = runtime.connectForAddDevice(deviceId)

    override fun showHeadphones() {
        mutableHomeNavigationRequest.update { it + 1L }
    }
}

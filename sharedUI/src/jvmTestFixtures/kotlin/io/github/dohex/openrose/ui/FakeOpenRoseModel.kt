package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.OpenRoseModel
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Adapts fixture state to the same observable interface used by the real model. */
@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
private class SnapshotStateFlow<T>(private val source: MutableState<T>) : StateFlow<T> {
    override val value: T get() = source.value
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        snapshotFlow { source.value }.collect(collector)
        awaitCancellation()
    }
}

internal class FakeOpenRoseModel(
    val mutableState: MutableState<ControlState> = mutableStateOf(ControlState(platform = PlatformState(PermissionState.NOT_REQUIRED))),
    val mutableUiSettings: MutableState<UiSettings> = mutableStateOf(UiSettings()),
) : OpenRoseModel {
    override val state: StateFlow<ControlState> = SnapshotStateFlow(mutableState)
    override val uiSettings: StateFlow<UiSettings> = SnapshotStateFlow(mutableUiSettings)
    val homeNavigationRequests = MutableStateFlow(0L)
    override val homeNavigationRequest: StateFlow<Long> = homeNavigationRequests

    val settingsChanges = mutableListOf<UiSettings>()
    val controlSettingsChanges = mutableListOf<UserSettings>()
    val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
    val connectRequests = mutableListOf<DeviceId>()
    var disconnectCount = 0
        private set

    var onStartScan: () -> Unit = {}
    var onStopScan: () -> Unit = {}
    var onConnect: (DeviceId) -> Unit = {}
    var onDisconnect: () -> Unit = {}
    var onSet: (DeviceSetting, FieldValue) -> Unit = { _, _ -> }
    var onSetInSession: suspend (Long, DeviceSetting, FieldValue) -> OperationResult = { _, setting, value ->
        onSet(setting, value)
        OperationResult.Success
    }
    var onAddDeviceConnect: suspend (DeviceId) -> OperationResult = {
        error("Unexpected add-device connection in this scenario")
    }
    var onShowHeadphones: () -> Unit = {}

    override fun updateUiSettings(settings: UiSettings) {
        settingsChanges += settings
        mutableUiSettings.value = settings
    }

    override fun updateSettings(settings: UserSettings) {
        controlSettingsChanges += settings
        mutableState.value = mutableState.value.copy(settings = settings)
    }

    override fun startScan() = onStartScan()
    override fun stopScan() = onStopScan()
    override fun connect(deviceId: DeviceId) {
        connectRequests += deviceId
        onConnect(deviceId)
    }
    override fun disconnect() {
        disconnectCount++
        onDisconnect()
    }
    override fun set(setting: DeviceSetting, value: FieldValue) {
        settingWrites += setting to value
        onSet(setting, value)
    }
    override suspend fun setInSession(sessionId: Long, setting: DeviceSetting, value: FieldValue): OperationResult {
        settingWrites += setting to value
        return onSetInSession(sessionId, setting, value)
    }
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = onAddDeviceConnect(deviceId)
    override fun showHeadphones() {
        homeNavigationRequests.value++
        onShowHeadphones()
    }
}

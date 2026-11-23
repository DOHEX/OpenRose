package io.github.dohex.openrose.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.OpenRoseModel
import io.github.dohex.openrose.ui.model.WindowNavigation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
private class SnapshotStateFlow<T>(private val source: MutableState<T>) : StateFlow<T> {
    override val value: T get() = source.value
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        snapshotFlow { source.value }.collect(collector)
        awaitCancellation()
    }
}

class FakeOpenRoseModel(
    val mutableState: MutableState<ControlState> = mutableStateOf(ControlState(platform = PlatformState(PermissionState.NOT_REQUIRED))),
    val mutableUiSettings: MutableState<UiSettings> = mutableStateOf(UiSettings()),
) : OpenRoseModel {
    override val state: StateFlow<ControlState> = SnapshotStateFlow(mutableState)
    override val uiSettings: StateFlow<UiSettings> = SnapshotStateFlow(mutableUiSettings)
    override val navigation = WindowNavigation()
    override val notice = MutableStateFlow<String?>(null)

    val settingsChanges = mutableListOf<UiSettings>()
    val controlSettingsChanges = mutableListOf<UserSettings>()
    val settingWrites = mutableListOf<Pair<DeviceSetting, FieldValue>>()
    val connectRequests = mutableListOf<DeviceId>()
    var disconnectCount = 0
        private set

    var onStartScan: () -> Unit = {}
    var startScanResult: OperationResult = OperationResult.Success
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

    override fun updateUiSettings(settings: UiSettings) {
        settingsChanges += settings
        mutableUiSettings.value = settings
    }

    override fun updateSettings(settings: UserSettings) {
        controlSettingsChanges += settings
        mutableState.value = mutableState.value.copy(settings = settings)
    }

    override fun startScan(): CompletableDeferred<OperationResult> {
        onStartScan()
        return CompletableDeferred(startScanResult)
    }
    override fun stopScan() = onStopScan()
    override fun connect(deviceId: DeviceId) {
        connectRequests += deviceId
        onConnect(deviceId)
    }
    override fun disconnect() {
        disconnectCount++
        onDisconnect()
    }
    override fun set(sessionId: Long, command: HeadsetCommand) {
        val session = state.value.sessionFor(command.setting)
        if (session?.sessionId != sessionId) return
        settingWrites += command.setting to command.value
        onSet(command.setting, command.value)
    }
    override suspend fun setInSession(sessionId: Long, command: HeadsetCommand): OperationResult {
        settingWrites += command.setting to command.value
        return onSetInSession(sessionId, command.setting, command.value)
    }
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = onAddDeviceConnect(deviceId)
}

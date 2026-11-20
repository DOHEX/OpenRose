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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Pages the system and compact panels can open in the main window. */
enum class WindowDestination { HEADPHONES, ADD_DEVICE, DIAGNOSTICS }

/** Holds the latest request while the window is closed. A reopened window only receives unhandled requests. */
class WindowNavigation {
    private val destinations = Channel<WindowDestination>(Channel.CONFLATED)
    internal val requests = destinations.receiveAsFlow()

    fun open(destination: WindowDestination) {
        destinations.trySend(destination)
    }
}

/** Presentation state and intents shared by every page and component style. */
interface OpenRoseModel {
    val state: StateFlow<ControlState>
    val uiSettings: StateFlow<UiSettings>
    val navigation: WindowNavigation

    /**
     * A sentence the host wants the user to read on the 我的耳机 page, such as why a notification opened here.
     * The host posts it and the page sets it back to null when the user dismisses it.
     */
    val notice: MutableStateFlow<String?>

    fun updateUiSettings(settings: UiSettings)
    fun updateSettings(settings: UserSettings)
    fun startScan()
    fun stopScan()
    fun connect(deviceId: DeviceId)
    fun disconnect()
    fun set(setting: DeviceSetting, value: FieldValue)

    /** Writes to [sessionId] only, and returns once the device confirms the setting or the write ends otherwise. */
    suspend fun setInSession(sessionId: Long, setting: DeviceSetting, value: FieldValue): OperationResult
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult
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
    override val navigation = WindowNavigation()
    override val notice = MutableStateFlow<String?>(null)

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

    override suspend fun setInSession(sessionId: Long, setting: DeviceSetting, value: FieldValue) = runtime.set(setting, value, sessionId)

    // The adding flow owns cancellation of its unfinished connection attempt.
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = runtime.connectForAddDevice(deviceId)
}

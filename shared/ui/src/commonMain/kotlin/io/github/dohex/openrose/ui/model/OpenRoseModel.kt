package io.github.dohex.openrose.ui.model

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WindowDestination { HEADPHONES, ADD_DEVICE, DIAGNOSTICS, TRAY_PANEL_SETTINGS }

class WindowNavigation {
    private val destinations = Channel<WindowDestination>(Channel.CONFLATED)
    internal val requests = destinations.receiveAsFlow()

    fun open(destination: WindowDestination) {
        destinations.trySend(destination)
    }
}

interface OpenRoseModel {
    val state: StateFlow<ControlState>
    val uiSettings: StateFlow<UiSettings>
    val navigation: WindowNavigation

    val notice: MutableStateFlow<String?>

    fun updateUiSettings(settings: UiSettings)
    fun updateSettings(settings: UserSettings)
    fun startScan(): Deferred<OperationResult>
    fun stopScan()
    fun connect(deviceId: DeviceId)
    fun disconnect()
    fun set(sessionId: Long, command: HeadsetCommand)

    suspend fun setInSession(sessionId: Long, command: HeadsetCommand): OperationResult
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult
}

internal fun OpenRoseModel.settingsFor(sessionId: Long): (HeadsetCommand) -> Unit = { command ->
    set(sessionId, command)
}

interface OpenRoseSettingsStore {
    val uiSettings: StateFlow<UiSettings>
    fun updateUiSettings(settings: UiSettings)
    fun updateSettings(settings: UserSettings)
}

class RuntimeOpenRoseModel(
    private val runtime: ControlRuntime,
    private val settingsStore: OpenRoseSettingsStore,
    private val scope: CoroutineScope,
) : OpenRoseModel {
    private val scanCommands = Mutex()

    override val state: StateFlow<ControlState> = runtime.state
    override val uiSettings: StateFlow<UiSettings> = settingsStore.uiSettings
    override val navigation = WindowNavigation()
    override val notice = MutableStateFlow<String?>(null)

    override fun updateUiSettings(settings: UiSettings) = settingsStore.updateUiSettings(settings)
    override fun updateSettings(settings: UserSettings) = settingsStore.updateSettings(settings)
    override fun startScan(): Deferred<OperationResult> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        scanCommands.withLock { runtime.startScan() }
    }
    override fun stopScan() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scanCommands.withLock { runtime.stopScan() } }
    }
    override fun connect(deviceId: DeviceId) {
        scope.launch { runtime.connect(deviceId) }
    }
    override fun disconnect() {
        scope.launch { runtime.disconnect() }
    }
    override fun set(sessionId: Long, command: HeadsetCommand) {
        scope.launch { setInSession(sessionId, command) }
    }

    override suspend fun setInSession(sessionId: Long, command: HeadsetCommand) = runtime.set(command, sessionId)

    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = runtime.connectForAddDevice(deviceId)
}

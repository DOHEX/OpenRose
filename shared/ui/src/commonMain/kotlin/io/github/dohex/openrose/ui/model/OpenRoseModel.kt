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
    fun startScan(): Deferred<OperationResult>
    fun stopScan()
    fun connect(deviceId: DeviceId)
    fun disconnect()
    fun set(sessionId: Long, command: HeadsetCommand)

    /** Writes to [sessionId] only, and returns once the device confirms the setting or the write ends otherwise. */
    suspend fun setInSession(sessionId: Long, command: HeadsetCommand): OperationResult
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult
}

/** Captures the session shown by a control, including callbacks delivered after the device changes. */
internal fun OpenRoseModel.settingsFor(sessionId: Long): (HeadsetCommand) -> Unit = { command ->
    set(sessionId, command)
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
    // Enter the mutex on the caller's turn so late scan acknowledgements cannot reorder start, stop and re-entry.
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

    // The adding flow owns cancellation of its unfinished connection attempt.
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = runtime.connectForAddDevice(deviceId)
}

package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ui.model.OpenRoseModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** How long a search runs before it stops by itself. */
private const val SEARCH_TIMEOUT_MILLIS = 20_000L

/** Lives above the style and navigation-entry compositions, so rendering never owns a connection. */
@Stable
internal class AddDeviceFlow(
    private val scope: CoroutineScope,
    private val model: OpenRoseModel,
) {
    var selectedDeviceId by mutableStateOf<DeviceId?>(null)
        private set
    var connectionError by mutableStateOf<ControlError?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var scanError by mutableStateOf<ControlError?>(null)
        private set

    /** The search ended because [SEARCH_TIMEOUT_MILLIS] ran out, not because the user or the screen stopped it. */
    var scanTimedOut by mutableStateOf(false)
        private set
    private var scanRequest: Deferred<OperationResult>? = null
    private var reference by mutableStateOf<SessionReference?>(null)
    private var connectionJob: Job? = null
    private var request = 0L

    var completed by mutableStateOf(false)
        private set

    fun startSearch() {
        if (pending || scanRequest?.isActive == true || !model.state.value.canSearchForHeadphones()) return
        scanError = null
        scanTimedOut = false
        selectedDeviceId = null
        connectionError = null
        reference = null
        val started = model.startScan()
        scanRequest = started
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = started.await()
            if (scanRequest === started && result is OperationResult.Failure) scanError = result.error
        }
    }

    fun stopSearch() {
        if (scanRequest == null) return
        scanRequest = null
        model.stopScan()
    }

    /** The search timer ran out: stops like [stopSearch] and lets the screen say so. */
    fun stopSearchAfterTimeout() {
        if (scanRequest == null) return
        scanTimedOut = true
        stopSearch()
    }

    fun connect(deviceId: DeviceId) {
        if (pending || !model.state.value.canSearchForHeadphones()) return
        stopSearch()
        selectedDeviceId = deviceId
        reference = null
        connectionError = null
        pending = true
        val currentRequest = ++request
        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = model.connectForAddDevice(deviceId)
                coroutineContext.ensureActive()
                if (request == currentRequest) {
                    pending = false
                    when (result) {
                        OperationResult.Success -> completed = true
                        is OperationResult.Failure -> connectionError = result.error
                    }
                }
            } finally {
                if (request == currentRequest) pending = false
            }
        }
    }

    fun observe(connection: ConnectionState) {
        if (!pending || reference != null) return
        reference = when (connection) {
            is ConnectionState.Connecting ->
                if (connection.deviceId == selectedDeviceId) SessionReference(connection.deviceId, connection.sessionId) else null

            is ConnectionState.Connected ->
                if (connection.deviceId == selectedDeviceId) SessionReference(connection.deviceId, connection.sessionId) else null

            ConnectionState.Disconnected -> null
        }
    }

    fun phase(connection: ConnectionState): ConnectionPhase? {
        if (!pending) return null
        val selected = reference ?: return ConnectionPhase.CONNECTING
        return when (connection) {
            is ConnectionState.Connecting ->
                if (connection.deviceId == selected.deviceId && connection.sessionId == selected.sessionId) {
                    connection.phase
                } else {
                    ConnectionPhase.CONNECTING
                }

            is ConnectionState.Connected ->
                if (connection.deviceId == selected.deviceId && connection.sessionId == selected.sessionId) {
                    connection.phase
                } else {
                    ConnectionPhase.CONNECTING
                }

            ConnectionState.Disconnected -> ConnectionPhase.CONNECTING
        }
    }

    fun cancelConnection() {
        request++
        connectionJob?.cancel()
        connectionJob = null
        pending = false
        reference = null
        connectionError = null
    }

    fun leave() {
        cancelConnection()
        stopSearch()
        selectedDeviceId = null
        scanError = null
        scanTimedOut = false
        completed = false
    }
}

@Composable
internal expect fun rememberAddDeviceFlow(model: OpenRoseModel): AddDeviceFlow

/** Detaching a rendition stops discovery. Leaving the route also cancels the attempt it started. */
@Composable
internal fun AddDeviceLifecycle(active: Boolean, state: ControlState, flow: AddDeviceFlow, onReady: () -> Unit) {
    val canScan = state.canSearchForHeadphones()
    DisposableEffect(flow, active) {
        if (!active) flow.leave()
        onDispose { if (active) flow.stopSearch() }
    }
    LaunchedEffect(flow, active, canScan) {
        if (active) {
            if (!canScan) {
                flow.leave()
            } else if (flow.selectedDeviceId == null) {
                flow.startSearch()
            }
        }
    }
    LaunchedEffect(flow, active, flow.completed) {
        if (active && flow.completed) onReady()
    }
    LaunchedEffect(flow, active, canScan, state.scan, flow.pending) {
        val searching = state.scan == ScanState.Scanning && !flow.pending
        if (active && canScan && searching) {
            delay(SEARCH_TIMEOUT_MILLIS)
            flow.stopSearchAfterTimeout()
        }
    }
    SideEffect { flow.observe(state.connection) }
}

internal fun ControlState.canSearchForHeadphones(): Boolean = platform.connectionBlockReason == null && !platform.scanPermission.missing

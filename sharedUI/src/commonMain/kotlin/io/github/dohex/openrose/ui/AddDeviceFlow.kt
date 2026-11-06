package io.github.dohex.openrose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlError
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.ScanState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.TransportAvailability
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Lives above the style and navigation-entry compositions, so rendering never owns a connection. */
@Stable
internal class AddDeviceFlow(
    private val scope: CoroutineScope,
    private val state: () -> ControlState,
    private val startScan: () -> Unit,
    private val stopScan: () -> Unit,
    private val connect: suspend (DeviceId) -> OperationResult,
    private val onReady: () -> Unit,
) {
    var selectedDeviceId by mutableStateOf<DeviceId?>(null)
        private set
    var connectionError by mutableStateOf<ControlError?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    private var reference by mutableStateOf<SessionReference?>(null)
    private var connectionJob: Job? = null
    private var request = 0L

    fun enter() {
        selectedDeviceId = null
        connectionError = null
        reference = null
    }

    fun startSearch() {
        if (pending || !state().canSearchForHeadphones()) return
        selectedDeviceId = null
        connectionError = null
        reference = null
        startScan()
    }

    fun stopSearch() {
        if (state().scan == ScanState.Scanning) stopScan()
    }

    fun connect(deviceId: DeviceId) {
        if (pending || !state().canSearchForHeadphones()) return
        stopSearch()
        selectedDeviceId = deviceId
        reference = null
        connectionError = null
        pending = true
        val currentRequest = ++request
        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = connect.invoke(deviceId)
                coroutineContext.ensureActive()
                if (request == currentRequest) {
                    pending = false
                    when (result) {
                        OperationResult.Success -> onReady()
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
    }
}

@Composable
internal fun rememberAddDeviceFlow(
    active: Boolean,
    state: ControlState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: suspend (DeviceId) -> OperationResult,
    onReady: () -> Unit,
): AddDeviceFlow {
    val scope = rememberCoroutineScope()
    val latestState by rememberUpdatedState(state)
    val startScan by rememberUpdatedState(onStartScan)
    val stopScan by rememberUpdatedState(onStopScan)
    val connect by rememberUpdatedState(onConnect)
    val ready by rememberUpdatedState(onReady)
    val flow = remember {
        AddDeviceFlow(scope, { latestState }, { startScan() }, { stopScan() }, { connect(it) }, { ready() })
    }
    val canScan = state.canSearchForHeadphones()
    DisposableEffect(active) {
        if (active) flow.enter()
        onDispose { if (active) flow.leave() }
    }
    LaunchedEffect(active, canScan) {
        if (active) {
            if (canScan) {
                flow.startSearch()
            } else {
                flow.stopSearch()
                flow.cancelConnection()
            }
        }
    }
    LaunchedEffect(active, canScan, state.scan, flow.pending) {
        if (active && canScan && state.scan == ScanState.Scanning && !flow.pending) {
            delay(20_000)
            flow.stopSearch()
        }
    }
    SideEffect { flow.observe(state.connection) }
    return flow
}

internal fun ControlState.canSearchForHeadphones(): Boolean = platform.transport == TransportAvailability.Available &&
    (platform.permission == PermissionState.GRANTED || platform.permission == PermissionState.NOT_REQUIRED)

package io.github.dohex.openrose.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal val addDeviceCandidate = DeviceCandidate(
    DeviceId("AA:BB:CC:DD:EE:01"),
    "ROSE EarFree i5",
    -50,
    ModelProfile.I5_ZT,
    "",
)

/** The add-device flow on a fake control entry; each scan start records the virtual time it happened at. */
internal fun withAddDeviceHarness(
    style: InterfaceStyle = InterfaceStyle.MIUIX,
    initial: ControlState = ControlState(
        platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
        candidates = persistentListOf(addDeviceCandidate),
    ),
    app: Boolean = false,
    width: Int = 420,
    height: Int = 1000,
    block: suspend (AddDeviceUiTestHarness) -> Unit,
) = runOpenRoseUiTest(width, height) {
    val harness = AddDeviceUiTestHarness(this, mutableStateOf(initial), mutableStateOf(UiSettings(appearance = Appearance.LIGHT, interfaceStyle = style, pageTransition = PageTransition.NONE)))
    val state = harness.state
    val model = FakeOpenRoseModel(state, harness.uiSettings).apply {
        onStartScan = {
            harness.scanStarts++
            harness.scanStartTimes += harness.currentTime
            state.value = state.value.copy(scan = ScanState.Scanning, lastError = null)
        }
        onStopScan = {
            harness.scanStops++
            state.value = state.value.copy(scan = ScanState.Idle)
        }
        onConnect = { harness.beginConnection(it) }
        onDisconnect = {
            harness.disconnects++
            state.value = state.value.copy(connection = ConnectionState.Disconnected, device = null)
        }
        onAddDeviceConnect = { harness.connectForAddDevice(it) }
    }
    val platformUi = object : PlatformUi {
        override val platformName = "Test"
        override val requestBluetoothPermission: (() -> Unit) = {}
        override val openBluetoothSettings: (() -> Unit) = {}

        @androidx.compose.runtime.Composable
        override fun DiagnosticsEntry(onDiagnostics: () -> Unit) {}
    }
    harness.app = app
    setContent {
        if (app) {
            OpenRoseApp(model, platformUi)
        } else {
            val flow = rememberAddDeviceFlow(
                active = true,
                state = state.value,
                onStartScan = model::startScan,
                onStopScan = model::stopScan,
                onConnect = model::connectForAddDevice,
                onReady = {},
            )
            ProvideInterfaceStyle(harness.uiSettings.value.interfaceStyle, dark = false) {
                AddDeviceContent(
                    state = state.value,
                    canScan = state.value.canSearchForHeadphones(),
                    selectedDeviceId = flow.selectedDeviceId,
                    connectionPhase = flow.phase(state.value.connection),
                    connectionError = flow.connectionError,
                    onRequestPermission = platformUi.requestBluetoothPermission,
                    onOpenSystemSettings = platformUi.openBluetoothSettings,
                    onStartScan = flow::startSearch,
                    onStopScan = flow::stopSearch,
                    onConnect = flow::connect,
                    onCancelConnection = flow::cancelConnection,
                    onDiagnostics = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
    block(harness)
}

internal class AddDeviceUiTestHarness(
    scene: OpenRoseUiTestScene,
    val state: MutableState<ControlState>,
    val uiSettings: MutableState<UiSettings>,
) : OpenRoseUiTestHarness(scene) {
    var app = false
    var scanStarts = 0
    var scanStops = 0
    val scanStartTimes = mutableListOf<Long>()
    var disconnects = 0
    val connectRequests = mutableListOf<DeviceId>()
    private var nextSessionId = 1L

    fun openAddDevice() {
        render()
        if (app) clickContentDescription("添加耳机")
        render()
    }

    fun beginConnection(deviceId: DeviceId) {
        connectRequests += deviceId
        val candidate = state.value.candidates.first { it.deviceId == deviceId }
        val profile = requireNotNull(candidate.profile)
        val sessionId = nextSessionId++
        state.value = state.value.copy(
            scan = ScanState.Idle,
            lastError = null,
            connection = ConnectionState.Connecting(deviceId, sessionId, ConnectionPhase.CONNECTING),
            device = DeviceState(deviceId, sessionId, profile, DeviceCapabilities(persistentSetOf()), persistentMapOf()),
        )
    }

    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult {
        beginConnection(deviceId)
        val attempt = state.value.connection as ConnectionState.Connecting
        try {
            val terminal = snapshotFlow { state.value.connection }.first { connection ->
                when (connection) {
                    is ConnectionState.Connecting -> connection.deviceId != attempt.deviceId || connection.sessionId != attempt.sessionId

                    is ConnectionState.Connected ->
                        connection.deviceId != attempt.deviceId ||
                            connection.sessionId != attempt.sessionId || connection.phase == ConnectionPhase.READY

                    ConnectionState.Disconnected -> true
                }
            }
            return if (terminal is ConnectionState.Connected &&
                terminal.deviceId == attempt.deviceId && terminal.sessionId == attempt.sessionId &&
                terminal.phase == ConnectionPhase.READY
            ) {
                OperationResult.Success
            } else {
                OperationResult.Failure(state.value.lastError ?: ControlError(ControlErrorCode.DISCONNECTED, "连接已结束"))
            }
        } catch (canceled: CancellationException) {
            val current = state.value.connection
            if (current is ConnectionState.Connecting &&
                current.deviceId == attempt.deviceId && current.sessionId == attempt.sessionId
            ) {
                disconnects++
                state.value = state.value.copy(connection = ConnectionState.Disconnected, device = null)
            }
            throw canceled
        }
    }

    fun ready() {
        val device = requireNotNull(state.value.device)
        state.value = state.value.copy(
            connection = ConnectionState.Connected(device.deviceId, device.sessionId, device.profile),
        )
    }
}

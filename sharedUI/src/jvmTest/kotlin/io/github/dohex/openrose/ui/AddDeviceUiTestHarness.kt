package io.github.dohex.openrose.ui

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import io.github.dohex.openrose.core.*
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal val addDeviceCandidate = DeviceCandidate(
    DeviceId("AA:BB:CC:DD:EE:01"), "ROSE EarFree i5", -50, ModelProfile.I5_ZT, "",
)

/** The add-device flow on a fake control entry; each scan start records the virtual time it happened at. */
internal fun withAddDeviceHarness(
    style: InterfaceStyle = InterfaceStyle.MIUIX,
    initial: ControlState = ControlState(
        platform = PlatformState(PermissionState.GRANTED, TransportAvailability.Available),
        candidates = persistentListOf(addDeviceCandidate),
    ),
    width: Int = 420,
    height: Int = 1000,
    block: suspend (AddDeviceUiTestHarness) -> Unit,
) = runOpenRoseUiTest(width, height) {
    val harness = AddDeviceUiTestHarness(this, mutableStateOf(initial), mutableStateOf(UiSettings(appearance = Appearance.LIGHT, interfaceStyle = style, pageTransition = PageTransition.NONE)))
    val state = harness.state
    setContent {
        OpenRoseApp(
            state = state.value,
            uiSettings = harness.uiSettings.value,
            onUiSettingsChange = { harness.uiSettings.value = it },
            onSettingsChange = { state.value = state.value.copy(settings = it) },
            onRequestPermission = {},
            onOpenSystemSettings = {},
            onStartScan = {
                harness.scanStarts++
                harness.scanStartTimes += harness.currentTime
                state.value = state.value.copy(scan = ScanState.Scanning, lastError = null)
            },
            onStopScan = {
                harness.scanStops++
                state.value = state.value.copy(scan = ScanState.Idle)
            },
            onConnect = { harness.beginConnection(it) },
            onDisconnect = {
                harness.disconnects++
                state.value = state.value.copy(connection = ConnectionState.Disconnected, device = null)
            },
            onSet = { _, _ -> },
            onAddDeviceConnect = { harness.connectForAddDevice(it) },
        )
    }
    block(harness)
}

internal class AddDeviceUiTestHarness(
    scene: OpenRoseUiTestScene,
    val state: MutableState<ControlState>,
    val uiSettings: MutableState<UiSettings>,
) : OpenRoseUiTestHarness(scene) {
    var scanStarts = 0
    var scanStops = 0
    val scanStartTimes = mutableListOf<Long>()
    var disconnects = 0
    val connectRequests = mutableListOf<DeviceId>()
    private var nextSessionId = 1L

    fun openAddDevice() {
        render()
        clickContentDescription("添加耳机")
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
                    is ConnectionState.Connected -> connection.deviceId != attempt.deviceId ||
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

package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

data class SessionTiming(
    val connectTimeoutMillis: Long = 15_000,
    val queryTimeoutMillis: Long = 1_500,
    val writeIntervalMillis: Long? = null,
    val writeTimeoutMillis: Long = 5_000,
) {
    init {
        require(connectTimeoutMillis > 0 && queryTimeoutMillis > 0 && writeTimeoutMillis > 0 && (writeIntervalMillis ?: 0) >= 0)
    }
}

internal class ControlSession(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
    transport: ControlTransport? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    timing: SessionTiming = SessionTiming(),
    sessionIdBase: Long = 0L,
    timeSource: TimeSource = TimeSource.Monotonic,
) : ControlRuntime {
    private val mutableState = MutableStateFlow(ControlState(platform = initialPlatformState, settings = initialSettings))
    override val state: StateFlow<ControlState> = mutableState.asStateFlow()
    private val settings = SettingQueue(mutableState, scope, timing)
    private val link = ControlLink(mutableState, transport, scope, timing, settings, sessionIdBase, timeSource)
    private val discovery = DeviceDiscovery(mutableState, transport, scope)
    private val targeting = ConnectionTargeting(mutableState, transport, scope, discovery, link)

    override suspend fun refreshBondedDevices() = discovery.refreshBondedDevices()
    override suspend fun startScan(): OperationResult = discovery.startScan()
    override suspend fun stopScan() = discovery.stopScan()
    override suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult = targeting.connect(deviceId, pinned)
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = targeting.connectForAddDevice(deviceId)
    override suspend fun disconnect(): Boolean = targeting.disconnect()
    override suspend fun release(): Boolean = link.disconnect(retire = true)

    override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult = settings.set(
        link,
        command,
        expectedSessionId,
    )

    override fun updateSettings(settings: UserSettings) {
        mutableState.update { it.copy(settings = settings) }
    }

    override fun updatePlatformState(platformState: PlatformState) = targeting.updatePlatformState(platformState)
}

internal fun platformError(state: ControlState, transport: ControlTransport?): ControlError? = when {
    state.platform.permission.missing ->
        ControlError(ControlErrorCode.PERMISSION_REQUIRED, "需要蓝牙权限")

    transport == null || state.platform.transport != TransportAvailability.Available ->
        ControlError(ControlErrorCode.TRANSPORT_UNAVAILABLE, "平台蓝牙传输不可用")

    else -> null
}

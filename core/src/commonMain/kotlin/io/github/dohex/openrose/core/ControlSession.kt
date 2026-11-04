package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Deadlines are injectable so public behavior can be tested with a virtual clock. */
data class SessionTiming(
    val connectTimeoutMillis: Long = 15_000,
    val queryTimeoutMillis: Long = 1_500,
    val i5WriteIntervalMillis: Long = 100,
    val i7WriteIntervalMillis: Long = 100,
) {
    init {
        require(connectTimeoutMillis > 0 && queryTimeoutMillis > 0 && i5WriteIntervalMillis >= 0 && i7WriteIntervalMillis >= 0)
    }
}

/**
 * Platform-owned authority behind [ControlRuntime]. Replacing a page or its interface style never
 * replaces this owner; callers outside the core module can only reach it through the runtime.
 */
internal class ControlSession(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
    transport: ControlTransport? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    timing: SessionTiming = SessionTiming(),
    /** Device session ids count up from here, so a reference from another owner instance never matches. */
    sessionIdBase: Long = 0L,
) : ControlRuntime {
    private val mutableState = MutableStateFlow(ControlState(platform = initialPlatformState, settings = initialSettings))
    override val state: StateFlow<ControlState> = mutableState.asStateFlow()
    private val settings = SettingQueue(mutableState, scope, timing)
    private val link = ControlLink(mutableState, transport, scope, timing, settings, sessionIdBase)
    private val discovery = DeviceDiscovery(mutableState, transport, scope)
    private val targeting = ConnectionTargeting(mutableState, transport, scope, discovery, link)

    override suspend fun refreshBondedDevices() = discovery.refreshBondedDevices()
    override suspend fun startScan(): OperationResult = discovery.startScan()
    override suspend fun stopScan() = discovery.stopScan()
    override suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult = targeting.connect(deviceId, pinned)
    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = targeting.connectForAddDevice(deviceId)
    override suspend fun disconnect(): Boolean = targeting.disconnect()

    override suspend fun set(setting: DeviceSetting, value: FieldValue, expectedSessionId: Long?): OperationResult =
        settings.set(link, setting, value, expectedSessionId)

    override fun updateSettings(settings: UserSettings) {
        mutableState.update { it.copy(settings = settings) }
    }

    override fun updatePlatformState(platformState: PlatformState) = targeting.updatePlatformState(platformState)
}

internal fun platformError(state: ControlState, transport: ControlTransport?): ControlError? = when {
    state.platform.permission == PermissionState.REQUIRED || state.platform.permission == PermissionState.DENIED ->
        ControlError(ControlErrorCode.PERMISSION_REQUIRED, "需要蓝牙权限")
    transport == null || state.platform.transport != TransportAvailability.Available ->
        ControlError(ControlErrorCode.TRANSPORT_UNAVAILABLE, "平台蓝牙传输不可用")
    else -> null
}

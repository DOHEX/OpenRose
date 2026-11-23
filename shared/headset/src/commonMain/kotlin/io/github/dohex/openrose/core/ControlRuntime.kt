package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

interface ControlRuntime {
    val state: StateFlow<ControlState>

    suspend fun refreshBondedDevices()

    suspend fun startScan(): OperationResult
    suspend fun stopScan()

    suspend fun connect(deviceId: DeviceId, pinned: Boolean = false): OperationResult

    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult

    suspend fun disconnect(): Boolean

    suspend fun release(): Boolean = disconnect()

    suspend fun set(command: HeadsetCommand, expectedSessionId: Long? = null): OperationResult

    fun updateSettings(settings: UserSettings)
    fun updatePlatformState(platformState: PlatformState)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
fun LocalControlRuntime(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
    transport: ControlTransport? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    timing: SessionTiming = SessionTiming(),
    sessionIdBase: Long = 0L,
): ControlRuntime = ControlSession(initialPlatformState, initialSettings, transport, scope, timing, sessionIdBase)

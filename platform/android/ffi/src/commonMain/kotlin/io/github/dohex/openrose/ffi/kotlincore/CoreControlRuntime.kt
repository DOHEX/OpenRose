package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.PlatformState
import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.ffi.RustControlResult
import io.github.dohex.openrose.ffi.RustControlRuntime
import io.github.dohex.openrose.ffi.SystemLocalClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.defaultSessionTiming
import uniffi.openrose_control.ControlSnapshot as RustSnapshot

class CoreControlRuntime internal constructor(
    private val rust: RustControlRuntime,
    scope: CoroutineScope,
    onClose: () -> Unit,
) : ControlRuntime {
    override val state: StateFlow<ControlState> = ProjectedStateFlow(rust.state, RustSnapshot::toControlState)

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            rust.close()
            onClose()
        }
    }

    override suspend fun refreshBondedDevices() = rust.refreshBondedHeadsets()

    override suspend fun startScan(): OperationResult = rust.startScan().toOperation()

    override suspend fun stopScan() = rust.stopScan()

    override suspend fun selectCurrentHeadset(deviceId: DeviceId) = rust.selectCurrentHeadset(deviceId.value)

    override suspend fun connect(deviceId: DeviceId, pinned: Boolean): OperationResult = rust.connect(deviceId.value, pinned).toOperation()

    override suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult = rust.connectForAddDevice(deviceId.value).toOperation()

    override suspend fun disconnect(): Boolean = rust.disconnect()

    override suspend fun release(): Boolean = rust.release()

    override suspend fun set(command: HeadsetCommand, expectedSessionId: Long?): OperationResult {
        val result = rust.set(command.toRust(), expectedSessionId?.toULong())
        return result.toOperation()
    }

    override fun updateSettings(settings: UserSettings) = rust.updateSettings(settings.toRust())

    override fun updatePlatformState(platformState: PlatformState) = rust.updatePlatformState(platformState.toRust())
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
fun CoreControlRuntime(
    transport: ControlTransport,
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
    scope: CoroutineScope,
    timing: SessionTiming = defaultSessionTiming(),
    sessionIdBase: Long = 0L,
    clock: LocalClock = SystemLocalClock(),
): ControlRuntime {
    val rust = RustControlRuntime(
        transport = transport,
        initialPlatformState = initialPlatformState.toRust(),
        initialSettings = initialSettings.toRust(),
        clock = clock,
        timing = timing,
        sessionIdBase = sessionIdBase.toULong(),
    )
    return CoreControlRuntime(rust, scope) { (transport as? AutoCloseable)?.close() }
}

private fun RustControlResult.toOperation(): OperationResult = when (this) {
    RustControlResult.Success -> OperationResult.Success
    is RustControlResult.Failure -> OperationResult.Failure(error.toKotlin())
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ProjectedStateFlow(
    private val source: StateFlow<RustSnapshot>,
    private val project: (RustSnapshot) -> ControlState,
) : StateFlow<ControlState> {
    @Volatile private var latest: Pair<RustSnapshot, ControlState>? = null

    override val value: ControlState get() = projected(source.value)

    override val replayCache: List<ControlState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<ControlState>): Nothing = source.collect { collector.emit(projected(it)) }

    private fun projected(snapshot: RustSnapshot): ControlState {
        latest?.takeIf { it.first === snapshot }?.let { return it.second }
        return project(snapshot).also { latest = snapshot to it }
    }
}

package io.github.dohex.openrose.ffi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.openrose_control.ControlError
import uniffi.openrose_control.ControlSettings
import uniffi.openrose_control.ControlSnapshot
import uniffi.openrose_control.PlatformState
import uniffi.openrose_control.SessionTiming
import uniffi.openrose_ffi.CirculationNode
import uniffi.openrose_ffi.ControlFailure
import uniffi.openrose_ffi.ControlObserver
import uniffi.openrose_ffi.ControlRuntime
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.LocalAudio
import uniffi.openrose_ffi.LocalClock
import uniffi.openrose_ffi.OwnerActive
import uniffi.openrose_ffi.PeerDiscovery
import uniffi.openrose_ffi.PeerRouteDialer
import uniffi.openrose_ffi.defaultSessionTiming
import uniffi.openrose_headset.HeadsetCommand
import uniffi.openrose_headset.HeadsetId
import uniffi.openrose_headset.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDateTime as JavaLocalDateTime

sealed interface RustControlResult {
    data object Success : RustControlResult
    data class Failure(val error: ControlError) : RustControlResult
}

class SystemLocalClock : LocalClock {
    override fun now(): LocalDateTime {
        val current = JavaLocalDateTime.now()
        return LocalDateTime(
            year = current.year.toUShort(),
            month = current.monthValue.toUByte(),
            day = current.dayOfMonth.toUByte(),
            hour = current.hour.toUByte(),
            minute = current.minute.toUByte(),
            second = current.second.toUByte(),
        )
    }
}

class RustControlRuntime(
    transport: ControlTransport,
    initialPlatformState: PlatformState,
    initialSettings: ControlSettings = ControlSettings(forceBle = false),
    clock: LocalClock = SystemLocalClock(),
    timing: SessionTiming = defaultSessionTiming(),
    sessionIdBase: ULong = 0uL,
) : AutoCloseable {
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private var pendingSnapshot: ControlSnapshot? = null
    private var mutableState: MutableStateFlow<ControlSnapshot>? = null

    private val observer = object : ControlObserver {
        override fun stateChanged(snapshot: ControlSnapshot) = publish(snapshot)
    }

    @PublishedApi
    internal val handle = ControlRuntime(
        transport = transport,
        observer = observer,
        clock = clock,
        platform = initialPlatformState,
        settings = initialSettings,
        timing = timing,
        sessionIdBase = sessionIdBase,
    )

    val state: StateFlow<ControlSnapshot>

    init {
        val initial = handle.snapshot()
        val flow = synchronized(stateLock) {
            val latest = pendingSnapshot?.takeIf { it.revision > initial.revision } ?: initial
            MutableStateFlow(latest).also { mutableState = it }
        }
        state = flow.asStateFlow()
    }

    inline fun <T> command(action: (ControlRuntime) -> T): T = try {
        action(handle)
    } finally {
        syncState()
    }

    suspend fun startScan(): RustControlResult = runControl { it.startScan() }

    suspend fun connect(headset: HeadsetId, pinned: Boolean = false): RustControlResult = runControl {
        if (pinned) it.connectPinned(headset) else it.connect(headset)
    }

    suspend fun connectForAddHeadset(headset: HeadsetId): RustControlResult = try {
        runControl { it.connectForAddHeadset(headset) }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { command { it.cancelAddHeadset() } }
        throw cancelled
    }

    suspend fun set(command: HeadsetCommand, expectedSession: ULong? = null): RustControlResult = runControl {
        it.set(command, expectedSession)
    }

    fun circulationNode(
        storageDir: String,
        name: String,
        listenPort: UShort? = null,
        discovery: PeerDiscovery,
        audio: LocalAudio,
        dialer: PeerRouteDialer,
        ownerActive: OwnerActive,
    ): CirculationNode = CirculationNode(
        storageDir = storageDir,
        name = name,
        listenPort = listenPort,
        control = handle,
        discovery = discovery,
        audio = audio,
        dialer = dialer,
        ownerActive = ownerActive,
    )

    override fun close() {
        closed.set(true)
        handle.close()
    }

    @PublishedApi
    internal fun syncState() {
        if (!closed.get()) publish(handle.snapshot())
    }

    private fun publish(snapshot: ControlSnapshot) {
        synchronized(stateLock) {
            val flow = mutableState
            if (flow == null) {
                val existing = pendingSnapshot
                if (existing == null || snapshot.revision >= existing.revision) {
                    pendingSnapshot = snapshot
                }
            } else if (snapshot.revision >= flow.value.revision) {
                flow.value = snapshot
            }
        }
    }

    private inline fun runControl(action: (ControlRuntime) -> Unit): RustControlResult = try {
        command(action)
        RustControlResult.Success
    } catch (failure: ControlFailure.Failed) {
        RustControlResult.Failure(failure.error)
    }
}

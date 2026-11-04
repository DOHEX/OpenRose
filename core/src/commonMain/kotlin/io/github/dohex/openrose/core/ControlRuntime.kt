package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * The single control entry every platform's interface and add-earphone flow share. Callers observe [state]
 * and submit intents here; the transport, queue, recovery and capability decisions stay behind it. No caller
 * holds another session, queue or connection, so a page, route or interface style change cannot replace
 * the session. Platform integrations that address one session by its [SessionReference] check the reference
 * against [state] themselves and then call this same entry.
 */
interface ControlRuntime {
    /** The only authoritative state: platform facts, user settings, candidates, connection and device. */
    val state: StateFlow<ControlState>

    /** Re-queries paired devices that the platform reports as supported candidates. */
    suspend fun refreshBondedDevices()

    suspend fun startScan(): OperationResult
    suspend fun stopScan()

    /**
     * Explicit connection; adopts [deviceId] as the target and replaces any earlier pinned one. With [pinned]
     * the target is kept against later audio-connection changes until another connection replaces it, as an
     * entry outside the interface (a notification, a device-center link) expects.
     */
    suspend fun connect(deviceId: DeviceId, pinned: Boolean = false): OperationResult

    /** Cancelling the adding flow releases only the unfinished attempt it created. */
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult

    /**
     * Ends only the control connection; the system audio connection is untouched. Returns whether the
     * connection is confirmed closed, so a caller never counts it as released on the strength of a failed close.
     */
    suspend fun disconnect(): Boolean

    /**
     * A setting succeeds only after the earphones confirm it, never when the write is accepted. With [session]
     * the setting is admitted only while that session is still the current one; a replaced session or another
     * device is refused without writing anything.
     */
    suspend fun set(setting: DeviceSetting, value: FieldValue, session: SessionReference? = null): OperationResult

    fun updateSettings(settings: UserSettings)
    fun updatePlatformState(platformState: PlatformState)
}

/** The runtime owned by this process: one [ControlSession] over the platform transport. */
fun LocalControlRuntime(
    initialPlatformState: PlatformState,
    initialSettings: UserSettings = UserSettings(),
    transport: ControlTransport? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    timing: SessionTiming = SessionTiming(),
    sessionIdBase: Long = 0L,
): ControlRuntime = ControlSession(initialPlatformState, initialSettings, transport, scope, timing, sessionIdBase)

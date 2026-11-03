package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * The single control entry shared by the application interface, add-earphone flow, system entries
 * and bridge consumers. Callers observe [state] and submit intents here; the transport, queue,
 * recovery and capability decisions stay behind it (ADR 0011). No caller holds another session,
 * queue or connection, so a page, route or interface style change cannot replace the owner.
 */
interface ControlRuntime {
    /** The only authoritative state: platform facts, user settings, candidates, connection and device. */
    val state: StateFlow<ControlState>

    /** Re-queries paired devices that the platform reports as supported candidates. */
    suspend fun refreshBondedDevices()

    suspend fun startScan(): OperationResult
    suspend fun stopScan()

    /** Explicit user connection; replaces any pinned system target and adopts [deviceId] as the target. */
    suspend fun connect(deviceId: DeviceId): OperationResult

    /** A system entry (notification, device-center link) pins [deviceId] as the target device. */
    suspend fun connectFromSystem(deviceId: DeviceId): OperationResult

    /** Cancelling the adding flow releases only the unfinished attempt it created. */
    suspend fun connectForAddDevice(deviceId: DeviceId): OperationResult

    /**
     * Ends only the control connection; the system audio connection is untouched. Returns whether the
     * connection is confirmed closed, so a handoff never counts an owner as released on the strength of a failed close.
     */
    suspend fun disconnect(): Boolean

    /** A setting succeeds only after the earphones confirm it, never when the write is accepted. */
    suspend fun set(setting: DeviceSetting, value: FieldValue): OperationResult

    /** As [set], but rejected unless [reference] still identifies the current device session. */
    suspend fun set(reference: SessionReference, setting: DeviceSetting, value: FieldValue): OperationResult

    /** Reads a detached snapshot only when [reference] still identifies the ready device session. */
    fun readSession(reference: SessionReference): SessionSnapshotResult

    /** The first current renderer to claim an active prompt becomes its sole trigger owner. */
    suspend fun claimConnectionPrompt(reference: SessionReference, promptId: String): Boolean

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
): ControlRuntime = ControlSession(initialPlatformState, initialSettings, transport, scope, timing)

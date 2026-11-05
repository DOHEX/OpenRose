package io.github.dohex.openrose.androidintegration

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** Why a host connection ended; process death is the only end that proves the owner is gone. */
data class LinkEnd(val processDied: Boolean, val reason: String)

/** The transport failed (the call could not be completed or its outcome is unknown). */
class HostLinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface LinkOpenResult {
    data class Open(val connection: HostConnection) : LinkOpenResult

    /** The endpoint could not be found, reached or authorised; [stage] says which. */
    data class Failed(val stage: OwnerFailureStage, val reason: String) : LinkOpenResult
}

/**
 * The application's side of the authenticated IPC to the module host. A platform binding (Binder on
 * Android) implements it; the application never sees Binder, bundles or credentials.
 */
fun interface ModuleHostLink {
    /** Discovers the host and authorises this caller. Finding the endpoint alone is not success. */
    suspend fun open(): LinkOpenResult
}

/** One authorised connection to one host incarnation. Calls throw [HostLinkException] when the outcome is unknown. */
interface HostConnection {
    val incarnation: String

    /** Status at the time of authorisation. */
    val initialStatus: HostStatus

    suspend fun status(): HostStatus
    suspend fun execute(generation: OwnerGeneration, command: HostCommand): HostCallResult
    suspend fun release(request: SwitchRequest): ReleaseResult
    suspend fun acquire(request: SwitchRequest, settings: HostSettings): AcquireResult

    /** The owner's states while [generation] holds control; completes when that generation ends. */
    fun frames(generation: OwnerGeneration): Flow<HostFrame>

    /** Completes when this connection can no longer be used. */
    val ended: Deferred<LinkEnd>

    fun close()
}

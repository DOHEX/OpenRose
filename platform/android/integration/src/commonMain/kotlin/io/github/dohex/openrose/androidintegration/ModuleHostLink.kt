package io.github.dohex.openrose.androidintegration

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

data class LinkEnd(val processDied: Boolean, val reason: String)

class HostLinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface LinkOpenResult {
    data class Open(val connection: HostConnection) : LinkOpenResult

    data class Failed(val stage: OwnerFailureStage, val reason: String) : LinkOpenResult
}

fun interface ModuleHostLink {
    suspend fun open(): LinkOpenResult
}

interface HostConnection {
    val incarnation: String

    val initialStatus: HostStatus

    suspend fun status(): HostStatus
    suspend fun execute(generation: OwnerGeneration, command: HostCommand): HostCallResult
    suspend fun release(request: SwitchRequest): ReleaseResult
    suspend fun acquire(request: SwitchRequest): AcquireResult

    fun frames(generation: OwnerGeneration): Flow<HostFrame>

    val ended: Deferred<LinkEnd>

    fun close()
}

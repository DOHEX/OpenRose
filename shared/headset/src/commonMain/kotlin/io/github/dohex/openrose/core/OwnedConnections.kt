package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.UnreleasedConnectionException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes native opens and releases, including handles left behind by an unsuccessful open. */
internal class OwnedConnections {
    private val lifecycle = Mutex()
    private val held = mutableSetOf<ControlConnection>()

    suspend fun open(create: suspend () -> ControlConnection): ControlConnection = lifecycle.withLock {
        currentCoroutineContext().ensureActive()
        try {
            create().also { held.add(it) }
        } catch (failure: UnreleasedConnectionException) {
            held.add(failure.connection)
            throw failure
        }
    }

    suspend fun close(connection: ControlConnection): Boolean = withContext(NonCancellable) {
        lifecycle.withLock { connection !in held || release(connection) }
    }

    suspend fun closeAll(): Boolean = withContext(NonCancellable) {
        lifecycle.withLock {
            held.toList().forEach { release(it) }
            held.isEmpty()
        }
    }

    private suspend fun release(connection: ControlConnection): Boolean = try {
        connection.close()
        held.remove(connection)
    } catch (_: Exception) {
        false
    }
}

package io.github.dohex.openrose.module

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.openrose_circulation.CirculationCommand
import uniffi.openrose_circulation.CirculationReply
import uniffi.openrose_circulation.RemoteError
import uniffi.openrose_ffi.CirculationNode

internal class CirculationSupervisor(
    scope: CoroutineScope,
    private val backoffMillis: Long = RESTART_BACKOFF_MILLIS,
    private val create: () -> CirculationNode,
) {
    @Volatile private var current: CirculationNode? = null

    init {
        scope.launch {
            while (isActive) restart()
        }
    }

    suspend fun execute(command: CirculationCommand): CirculationReply {
        val node = current ?: return CirculationReply.Failed(RemoteError.ServiceClosed)
        return node.execute(command)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun restart() {
        try {
            val node = withContext(Dispatchers.IO) { create() }
            try {
                node.start()
                current = node
                awaitCancellation()
            } finally {
                current = null
                withContext(NonCancellable) {
                    node.closeNode()
                    node.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            delay(backoffMillis)
        }
    }

    private companion object {
        const val RESTART_BACKOFF_MILLIS = 5_000L
    }
}

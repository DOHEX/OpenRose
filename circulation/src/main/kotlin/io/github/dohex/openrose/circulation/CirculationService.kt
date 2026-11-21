package io.github.dohex.openrose.circulation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Each activation owns a fresh listener. Cancelling its owner also releases discovery and all connections. */
class CirculationService {
    @Volatile private var current: CirculationNode? = null

    @Volatile private var unavailable = "互联服务尚未就绪"

    @Suppress("TooGenericExceptionCaught") // Platform discovery, disk-backed credentials and TLS can each fail independently.
    suspend fun serve(listenPort: Int = 0, create: () -> CirculationNode): Unit = withContext(Dispatchers.IO) {
        while (currentCoroutineContext().isActive) {
            try {
                val node = create()
                try {
                    node.start(listenPort)
                    current = node
                    awaitCancellation()
                } finally {
                    current = null
                    node.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                unavailable = failure.localizedMessage ?: "局域网发现不可用"
                delay(5_000)
            }
        }
    }

    fun call(fields: Map<String, String>): Map<String, String> = current?.localCommand(fields) ?: mapOf("error" to unavailable)
}

package io.github.dohex.openrose.framework

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancelling a Binder wait releases the caller; a blocked transact still occupies its slot until the host replies. */
internal class BinderCalls(maxConcurrent: Int) {
    private val slots = Semaphore(maxConcurrent)

    suspend fun <T> run(onAbandoned: (T) -> Unit = {}, call: () -> T): T {
        slots.acquire()
        return suspendCancellableCoroutine { waiting ->
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                try {
                    if (waiting.isActive) {
                        runCatching(call).fold(
                            onSuccess = { value -> waiting.resume(value) { _, reply, _ -> onAbandoned(reply) } },
                            onFailure = waiting::resumeWithException,
                        )
                    }
                } finally {
                    slots.release()
                }
            }
        }
    }
}

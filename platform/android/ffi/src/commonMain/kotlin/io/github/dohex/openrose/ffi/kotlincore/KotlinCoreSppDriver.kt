package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uniffi.openrose_control.SppFailureKind
import uniffi.openrose_ffi.SppDriver
import uniffi.openrose_ffi.SppDriverException
import java.io.IOException

internal class KotlinCoreSppDriver(private val link: SppLink, private val scope: CoroutineScope) : SppDriver {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile private var collector: Job? = null

    override fun maxWriteSize(): UInt = link.maxWriteSize.toUInt()

    override suspend fun connect() = guarded {
        link.open { }
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { collectChunks() }
        link.subscribe()
    }

    override suspend fun read(): ByteArray {
        val result = chunks.receiveCatching()
        result.getOrNull()?.let { return it }
        val cause = result.exceptionOrNull()
        throw SppDriverException.Failed(SppFailureKind.DISCONNECTED, cause?.message ?: LINK_CLOSED).also { failure ->
            cause?.let { failure.initCause(it) }
        }
    }

    override suspend fun write(value: ByteArray) = guarded { link.write(value) }

    override suspend fun closeLink() {
        guarded { link.close() }
        collector?.cancel()
        chunks.close(IOException(LINK_CLOSED))
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun collectChunks() {
        try {
            link.notifications.collect { chunks.send(it) }
            chunks.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            chunks.close(failure)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: SecurityException) {
        throw failed(SppFailureKind.PERMISSION, failure)
    } catch (failure: UnsupportedOperationException) {
        throw failed(SppFailureKind.UNSUPPORTED, failure)
    } catch (failure: IOException) {
        throw failed(SppFailureKind.DISCONNECTED, failure)
    }

    private fun failed(kind: SppFailureKind, cause: Throwable) = SppDriverException.Failed(kind, cause.message.orEmpty()).causedBy(cause)

    private companion object {
        const val LINK_CLOSED = "SPP 连接已关闭"
    }
}

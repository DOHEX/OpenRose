package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uniffi.openrose_control.SppFailureKind
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.SppDriver
import java.io.IOException

internal class KotlinCoreSppDriver(private val link: SppLink, private val scope: CoroutineScope) : SppDriver {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile private var collector: Job? = null

    override fun maxWriteSize(): UInt = link.maxWriteSize.toUInt()

    override suspend fun connect() = guarded {
        link.open { }
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications.pumpInto(chunks, onFailure = { chunks.close(it) }) { chunks.send(it) }
        }
        link.subscribe()
    }

    override suspend fun read(): ByteArray {
        val result = chunks.receiveCatching()
        result.getOrNull()?.let { return it }
        val cause = result.exceptionOrNull()
        throw DriverException.Spp(SppFailureKind.DISCONNECTED, cause?.message ?: LINK_CLOSED).also { failure ->
            cause?.let { failure.initCause(it) }
        }
    }

    override suspend fun write(value: ByteArray) = guarded { link.write(value) }

    override suspend fun closeLink() {
        guarded { link.close() }
        chunks.close(IOException(LINK_CLOSED))
        collector?.cancel()
    }

    private inline fun <T> guarded(block: () -> T): T = translatingFailures(
        onSecurity = { failed(SppFailureKind.PERMISSION, it) },
        onUnsupported = { failed(SppFailureKind.UNSUPPORTED, it) },
        onIo = { failed(SppFailureKind.DISCONNECTED, it) },
        block = block,
    )

    private fun failed(kind: SppFailureKind, cause: Throwable) = DriverException.Spp(kind, cause.message.orEmpty())

    private companion object {
        const val LINK_CLOSED = "SPP 连接已关闭"
    }
}

package io.github.dohex.openrose.ffi.drivers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> Flow<T>.pumpInto(
    channel: SendChannel<*>,
    onFailure: suspend (Exception) -> Unit = {},
    onEach: suspend (T) -> Unit,
) {
    try {
        collect(onEach)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    } finally {
        channel.close()
    }
}

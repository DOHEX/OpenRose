package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import uniffi.openrose_control.Advertisement
import uniffi.openrose_ffi.ScanDriver
import uniffi.openrose_ffi.TransportDriverException
import io.github.dohex.openrose.core.transport.Advertisement as KotlinAdvertisement

private const val ADVERTISEMENT_BUFFER = 256

internal class KotlinCoreScanDriver(private val source: Flow<KotlinAdvertisement>, scope: CoroutineScope) : ScanDriver {
    private val advertisements = Channel<Advertisement>(ADVERTISEMENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val collector = scope.launch { collect() }

    override suspend fun nextAdvertisement(): Advertisement? {
        val result = advertisements.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    override suspend fun stop() {
        collector.cancel()
        advertisements.close()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun collect() {
        try {
            source.collect { advertisements.send(it.toRust()) }
            advertisements.close()
        } catch (cancelled: CancellationException) {
            advertisements.close()
            throw cancelled
        } catch (failure: SecurityException) {
            advertisements.close(TransportDriverException.PermissionDenied(failure.message.orEmpty()))
        } catch (failure: Exception) {
            advertisements.close(TransportDriverException.Terminal(failure.message.orEmpty()))
        }
    }
}

private fun KotlinAdvertisement.toRust(): Advertisement = Advertisement(
    headsetId = deviceId.value,
    name = name,
    rssi = rssi?.toShort(),
    manufacturerData = manufacturerData.entries.associate { (company, data) -> company.toUShort() to data.copyOf() },
)

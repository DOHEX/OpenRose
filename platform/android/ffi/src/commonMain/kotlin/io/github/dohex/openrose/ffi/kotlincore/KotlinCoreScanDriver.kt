package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import uniffi.openrose_control.Advertisement
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.ScanDriver
import io.github.dohex.openrose.core.transport.Advertisement as KotlinAdvertisement

private const val ADVERTISEMENT_BUFFER = 256

internal class KotlinCoreScanDriver(source: Flow<KotlinAdvertisement>, scope: CoroutineScope) : ScanDriver {
    private val advertisements = Channel<Advertisement>(ADVERTISEMENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val collector = scope.launch {
        source.pumpInto(advertisements, onFailure = { advertisements.close(it.toScanFailure()) }) { advertisements.send(it.toRust()) }
    }

    override suspend fun nextAdvertisement(): Advertisement? {
        val result = advertisements.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    override suspend fun stop() {
        collector.cancel()
        advertisements.close()
    }
}

private fun Exception.toScanFailure(): DriverException = when (this) {
    is SecurityException -> DriverException.PermissionDenied(message.orEmpty())
    else -> DriverException.Terminal(message.orEmpty())
}

private fun KotlinAdvertisement.toRust(): Advertisement = Advertisement(
    headsetId = deviceId.value,
    name = name,
    rssi = rssi?.toShort(),
    manufacturerData = manufacturerData.entries.associate { (company, data) -> company.toUShort() to data.copyOf() },
)

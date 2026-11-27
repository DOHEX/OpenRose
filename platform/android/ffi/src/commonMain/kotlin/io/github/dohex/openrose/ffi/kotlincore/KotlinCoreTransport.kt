package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import uniffi.openrose_ffi.AudioFactsDriver
import uniffi.openrose_ffi.ControlTransport
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.GattDriver
import uniffi.openrose_ffi.ScanDriver
import uniffi.openrose_ffi.SppDriver
import uniffi.openrose_headset.HeadsetId

class KotlinCoreTransport(
    private val drivers: KotlinCoreDrivers,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ControlTransport,
    AutoCloseable {
    override fun openGatt(headset: HeadsetId): GattDriver = opening { KotlinCoreGattDriver(drivers.gatt(headset), scope) }

    override fun startScan(): ScanDriver = KotlinCoreScanDriver(drivers.scan(), scope)

    override fun openSpp(headset: HeadsetId, address: String, serviceUuid: String): SppDriver = opening {
        KotlinCoreSppDriver(drivers.spp(address, serviceUuid), scope)
    }

    override fun audioFacts(): AudioFactsDriver = KotlinCoreAudioFacts(drivers.audio, scope)

    override fun supportsInitializationRecovery(): Boolean = drivers.supportsInitializationRecovery

    override fun close() {
        scope.cancel()
    }

    private inline fun <T> opening(block: () -> T): T = translatingFailures(
        onSecurity = { DriverException.PermissionDenied(it.message.orEmpty()) },
        onUnsupported = { DriverException.Unsupported(it.message.orEmpty()) },
        onIo = { DriverException.Terminal(it.message.orEmpty()) },
        onInvalidArgument = { DriverException.InvalidArgument(it.message.orEmpty()) },
        block = block,
    )
}

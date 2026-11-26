package io.github.dohex.openrose.ffi.kotlincore

import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.AudioFactsDriver
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.GattDriver
import kotlinx.coroutines.flow.Flow

interface SppLink : ControlConnection {
    suspend fun open(onPhase: (ConnectionPhase) -> Unit)
}

interface KotlinCoreDrivers {
    val supportsInitializationRecovery: Boolean
    val audio: AudioFactsDriver

    fun gatt(address: String): GattDriver

    fun scan(): Flow<Advertisement>

    fun spp(address: String, serviceUuid: String): SppLink
}

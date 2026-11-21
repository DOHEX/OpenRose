package io.github.dohex.openrose.core.transport

import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.flow.StateFlow

/** A process-owned desktop transport also observes changes to the platform's Bluetooth adapter. */
interface DesktopControlTransport : ControlTransport {
    val availability: StateFlow<TransportAvailability>
    suspend fun close()
}

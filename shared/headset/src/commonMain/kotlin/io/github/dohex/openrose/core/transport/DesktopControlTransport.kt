package io.github.dohex.openrose.core.transport

import io.github.dohex.openrose.core.TransportAvailability
import kotlinx.coroutines.flow.StateFlow

interface DesktopControlTransport : ControlTransport {
    val availability: StateFlow<TransportAvailability>
    suspend fun close()
}

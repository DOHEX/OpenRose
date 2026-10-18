package io.github.dohex.openrose.core.transport

import io.github.dohex.openrose.core.ConnectionPhase
import kotlinx.coroutines.flow.Flow

@JvmInline
value class DeviceId(val value: String)

data class Advertisement(
    val deviceId: DeviceId,
    val name: String? = null,
    val rssi: Int? = null,
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
)

/** Protocol endpoints remain independent of any platform Bluetooth library. */
sealed interface TransportEndpoint {
    data class BleGatt(val serviceUuid: String, val writeUuid: String, val notifyUuid: String) : TransportEndpoint
}

interface ControlTransport {
    /** Completes when scanning stops; failures are reported through the flow. */
    fun scan(): Flow<Advertisement>
    suspend fun connect(
        deviceId: DeviceId,
        endpoint: TransportEndpoint,
        onPhase: (ConnectionPhase) -> Unit,
    ): ControlConnection
}

interface ControlConnection {
    /** Collect before subscribe(). Completion or failure means this connection has ended. */
    val notifications: Flow<ByteArray>
    /** Maximum complete payload accepted by write(), after transport overhead. */
    val maxWriteSize: Int
    suspend fun subscribe()
    /** Completes when the platform accepted the write, not when device state is confirmed. */
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

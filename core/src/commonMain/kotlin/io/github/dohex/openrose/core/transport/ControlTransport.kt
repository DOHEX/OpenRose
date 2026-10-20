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

enum class AudioProfileEvidence { UNKNOWN, CLASSIC, LE_AUDIO }

data class TransportFacts(
    val sppAvailable: Boolean = false,
    val audioProfile: AudioProfileEvidence = AudioProfileEvidence.UNKNOWN,
)

/** Protocol endpoints remain independent of any platform Bluetooth library. */
sealed interface TransportEndpoint {
    data class BleGatt(val serviceUuid: String, val writeUuid: String, val notifyUuid: String) : TransportEndpoint
    data class SppRfcomm(val address: String, val serviceUuid: String) : TransportEndpoint
}

enum class TransportSelectionReason {
    MODEL_BLE_ONLY,
    FORCED_BLE,
    SPP_UNAVAILABLE,
    CLASSIC_ADDRESS_UNAVAILABLE,
    AUDIO_PROFILE_UNKNOWN,
    LE_AUDIO,
    CLASSIC_AUDIO,
}

/** Failures that make a single setup retry on a fresh BLE connection worthwhile. */
enum class TransportRecoveryReason {
    SERVICE_DISCOVERY,
    SERVICES_UNAVAILABLE,
    SUBSCRIPTION,
    WRITE_FAILED,
    NO_INITIAL_RESPONSE,
    SERVICES_CHANGED,
}

/** A platform adapter uses this only for a setup failure it has classified as recoverable. */
class RecoverableTransportException(
    val reason: TransportRecoveryReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A transport fault explicitly classified by the adapter as terminal for this attempt. */
class TerminalTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class TransportSelection(
    val endpoint: TransportEndpoint,
    val reason: TransportSelectionReason,
)

interface ControlTransport {
    /** Completes when scanning stops; failures are reported through the flow. */
    fun scan(): Flow<Advertisement>
    /** Unclassified setup timeouts may be retried only when the adapter explicitly supports it. */
    val supportsInitializationRecovery: Boolean get() = false
    suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts = TransportFacts()
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

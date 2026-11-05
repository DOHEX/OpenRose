package io.github.dohex.openrose.core.transport

import kotlinx.coroutines.flow.Flow

/** A discovered instance, retained by identity across operations/events; UUIDs are canonical lowercase. */
class GattCharacteristic(
    val uuid: String,
    val canWrite: Boolean = false,
    val canWriteWithoutResponse: Boolean = false,
    val canNotify: Boolean = false,
    val canIndicate: Boolean = false,
)

data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)

enum class GattSubscription { DISABLED, NOTIFY, INDICATE }

enum class GattFailureKind { PERMISSION, UNSUPPORTED, INVALID_VALUE, DISCONNECTED, TRANSIENT, UNKNOWN }

/** Native drivers classify native status codes; shared code decides whether an operation can recover. */
class GattException(val kind: GattFailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface GattEvent {
    data class Notification(val characteristic: GattCharacteristic, val value: ByteArray) : GattEvent
    data object ServicesChanged : GattEvent
    data class Disconnected(val failure: GattException) : GattEvent
}

/**
 * One native connection. Collect events before connect. Methods finish at native completion and are
 * cancellable; close must also release an outstanding operation. No retries or payload policy here.
 */
interface GattDriver {
    val events: Flow<GattEvent>
    suspend fun connect()
    suspend fun discoverServices(): List<GattService>
    /** Null means unavailable; platforms without MTU negotiation report the negotiated MTU. */
    suspend fun requestMtu(preferred: Int): Int?
    /** Register/unregister local notification delivery, before/after remote configuration. */
    suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean)
    /** Configure the peer subscription; native descriptors stay inside the driver. */
    suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription)
    suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean)
    suspend fun close()
}

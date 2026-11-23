package io.github.dohex.openrose.core.transport

import kotlinx.coroutines.flow.Flow

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

class GattException(val kind: GattFailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface GattEvent {
    data class Notification(val characteristic: GattCharacteristic, val value: ByteArray) : GattEvent
    data object ServicesChanged : GattEvent
    data class Disconnected(val failure: GattException) : GattEvent
}

interface GattDriver {
    val events: Flow<GattEvent>
    suspend fun connect()
    suspend fun discoverServices(): List<GattService>

    suspend fun requestMtu(preferred: Int): Int?

    suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean)

    suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription)
    suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean)
    suspend fun close()
}

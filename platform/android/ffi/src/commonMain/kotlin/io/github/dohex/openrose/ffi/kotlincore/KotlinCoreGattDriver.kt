package io.github.dohex.openrose.ffi.kotlincore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uniffi.openrose_control.GattCharacteristic
import uniffi.openrose_control.GattError
import uniffi.openrose_control.GattEvent
import uniffi.openrose_control.GattFailureKind
import uniffi.openrose_control.GattService
import uniffi.openrose_control.GattSubscription
import uniffi.openrose_ffi.DriverException
import uniffi.openrose_ffi.GattDriver
import java.util.IdentityHashMap
import io.github.dohex.openrose.core.transport.GattCharacteristic as KotlinGattCharacteristic
import io.github.dohex.openrose.core.transport.GattDriver as KotlinGattDriver
import io.github.dohex.openrose.core.transport.GattEvent as KotlinGattEvent
import io.github.dohex.openrose.core.transport.GattException as KotlinGattException
import io.github.dohex.openrose.core.transport.GattFailureKind as KotlinGattFailureKind
import io.github.dohex.openrose.core.transport.GattService as KotlinGattService
import io.github.dohex.openrose.core.transport.GattSubscription as KotlinGattSubscription

private class CharacteristicTable(
    val byId: Map<ULong, KotlinGattCharacteristic>,
    val ids: IdentityHashMap<KotlinGattCharacteristic, ULong>,
)

@Suppress("TooManyFunctions")
internal class KotlinCoreGattDriver(private val driver: KotlinGattDriver, scope: CoroutineScope) : GattDriver {
    private val incoming = Channel<GattEvent>(Channel.UNLIMITED)
    private val pump: Job = scope.launch { pumpEvents() }

    @Volatile private var table = CharacteristicTable(emptyMap(), IdentityHashMap())

    override suspend fun nextEvent(): GattEvent? = incoming.receiveCatching().getOrNull()

    override suspend fun connect() = guarded { driver.connect() }

    override suspend fun discoverServices(): List<GattService> = publish(guarded { driver.discoverServices() })

    override suspend fun requestMtu(preferred: UShort): UShort? = guarded { driver.requestMtu(preferred.toInt()) }?.toUShort()

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) = guarded {
        driver.setNotificationRouting(resolve(characteristic), enabled)
    }

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) = guarded {
        driver.configureSubscription(resolve(characteristic), subscription.toKotlin())
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) = guarded {
        driver.write(resolve(characteristic), value, withResponse)
    }

    override suspend fun closeLink() {
        guarded { driver.close() }
        pump.cancel()
        incoming.close()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pumpEvents() {
        try {
            driver.events.collect { event -> event.toRust(table)?.let { incoming.send(it) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: KotlinGattException) {
            incoming.send(GattEvent.Disconnected(failure.toGattError()))
        } catch (failure: Exception) {
            incoming.send(GattEvent.Disconnected(GattError(GattFailureKind.UNKNOWN, failure.message.orEmpty())))
        } finally {
            incoming.close()
        }
    }

    private fun resolve(characteristic: GattCharacteristic): KotlinGattCharacteristic = table.byId[characteristic.instanceId]
        ?: throw DriverException.Gatt(GattFailureKind.UNSUPPORTED, "蓝牙特征不可用")

    private fun publish(services: List<KotlinGattService>): List<GattService> {
        val byId = LinkedHashMap<ULong, KotlinGattCharacteristic>()
        val ids = IdentityHashMap<KotlinGattCharacteristic, ULong>()
        val published = services.map { service ->
            GattService(
                service.uuid,
                service.characteristics.map { characteristic ->
                    val id = ids[characteristic] ?: (ids.size + 1).toULong().also {
                        ids[characteristic] = it
                        byId[it] = characteristic
                    }
                    GattCharacteristic(
                        id,
                        characteristic.uuid,
                        characteristic.canWrite,
                        characteristic.canWriteWithoutResponse,
                        characteristic.canNotify,
                        characteristic.canIndicate,
                    )
                },
            )
        }
        table = CharacteristicTable(byId, ids)
        return published
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: KotlinGattException) {
        throw DriverException.Gatt(failure.kind.toRust(), failure.message.orEmpty()).also { it.initCause(failure) }
    }
}

private fun KotlinGattEvent.toRust(table: CharacteristicTable): GattEvent? = when (this) {
    is KotlinGattEvent.Notification -> table.ids[characteristic]?.let { GattEvent.Notification(it, value) }
    KotlinGattEvent.ServicesChanged -> GattEvent.ServicesChanged
    is KotlinGattEvent.Disconnected -> GattEvent.Disconnected(failure.toGattError())
}

private fun KotlinGattException.toGattError(): GattError = GattError(kind.toRust(), message.orEmpty())

private fun KotlinGattFailureKind.toRust(): GattFailureKind = when (this) {
    KotlinGattFailureKind.PERMISSION -> GattFailureKind.PERMISSION
    KotlinGattFailureKind.UNSUPPORTED -> GattFailureKind.UNSUPPORTED
    KotlinGattFailureKind.INVALID_VALUE -> GattFailureKind.INVALID_VALUE
    KotlinGattFailureKind.DISCONNECTED -> GattFailureKind.DISCONNECTED
    KotlinGattFailureKind.TRANSIENT -> GattFailureKind.TRANSIENT
    KotlinGattFailureKind.UNKNOWN -> GattFailureKind.UNKNOWN
}

private fun GattSubscription.toKotlin(): KotlinGattSubscription = when (this) {
    GattSubscription.DISABLED -> KotlinGattSubscription.DISABLED
    GattSubscription.NOTIFY -> KotlinGattSubscription.NOTIFY
    GattSubscription.INDICATE -> KotlinGattSubscription.INDICATE
}

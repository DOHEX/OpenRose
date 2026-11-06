package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.dohex.openrose.core.transport.GattCharacteristic
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.core.transport.GattEvent
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.core.transport.GattService
import io.github.dohex.openrose.core.transport.GattSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import java.util.UUID

/** Android's native GATT operations; connection policy is owned by the shared layer. */
@SuppressLint("MissingPermission")
internal class AndroidGattDriver(
    private val context: Context,
    private val device: BluetoothDevice,
) : GattDriver {
    private val lock = Any()
    private val eventChannel = Channel<GattEvent>(Channel.BUFFERED)
    override val events = eventChannel.receiveAsFlow()
    private var client: BluetoothGatt? = null
    private var closed = false
    private var failure: GattException? = null
    private var releaseFailure: GattException? = null
    private var connectPending: CompletableDeferred<Unit>? = null
    private var discoveryPending: CompletableDeferred<List<GattService>>? = null
    private var mtuPending: CompletableDeferred<Int?>? = null
    private var descriptorPending: PendingDescriptor? = null
    private var writePending: PendingWrite? = null
    private var characteristics = IdentityHashMap<GattCharacteristic, BluetoothGattCharacteristic>()
    private var sharedCharacteristics = IdentityHashMap<BluetoothGattCharacteristic, GattCharacteristic>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                if (!current(gatt)) return
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val error = if (status == BluetoothGatt.GATT_SUCCESS) {
                        GattException(GattFailureKind.DISCONNECTED, "蓝牙连接已断开")
                    } else {
                        connectionFailure(status)
                    }
                    terminateFromCallback(error, notify = true)
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectPending?.complete(Unit)
                    connectPending = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(lock) {
                if (!current(gatt)) return
                val pending = discoveryPending ?: return
                discoveryPending = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pending.completeExceptionally(callbackFailure("蓝牙服务发现失败", status))
                    return
                }
                try {
                    val native = gatt.services
                    val byShared = IdentityHashMap<GattCharacteristic, BluetoothGattCharacteristic>()
                    val byNative = IdentityHashMap<BluetoothGattCharacteristic, GattCharacteristic>()
                    val services = native.map { service ->
                        GattService(
                            service.uuid.toString(),
                            service.characteristics.map { characteristic ->
                                byNative[characteristic] ?: run {
                                    val properties = characteristic.properties
                                    val shared = GattCharacteristic(
                                        characteristic.uuid.toString(),
                                        canWrite = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                                        canWriteWithoutResponse =
                                        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                                        canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                                        canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                                    )
                                    byShared[shared] = characteristic
                                    byNative[characteristic] = shared
                                    shared
                                }
                            },
                        )
                    }
                    characteristics = byShared
                    sharedCharacteristics = byNative
                    pending.complete(services)
                } catch (error: Exception) {
                    pending.completeExceptionally(mapException("读取蓝牙服务表失败", error))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(lock) {
                if (!current(gatt)) return
                val pending = mtuPending ?: return
                mtuPending = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pending.complete(mtu)
                } else {
                    pending.completeExceptionally(callbackFailure("蓝牙 MTU 协商失败", status))
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(lock) {
                if (!current(gatt)) return
                val pending = descriptorPending ?: return
                if (pending.descriptor !== descriptor) return
                descriptorPending = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pending.completion.complete(Unit)
                } else {
                    pending.completion.completeExceptionally(callbackFailure("通知配置失败", status))
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(lock) {
                if (!current(gatt)) return
                val pending = writePending ?: return
                if (pending.characteristic !== characteristic) return
                writePending = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pending.completion.complete(Unit)
                } else {
                    pending.completion.completeExceptionally(callbackFailure("蓝牙写入失败", status))
                }
            }
        }

        @Deprecated("Called by Android before API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            receive(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(gatt, characteristic, value)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            synchronized(lock) {
                if (!current(gatt)) return
                characteristics.clear()
                sharedCharacteristics.clear()
                if (eventChannel.trySend(GattEvent.ServicesChanged).isFailure) {
                    terminateFromCallback(eventOverflow(), notify = false, closeWithCause = true)
                }
            }
        }
    }

    override suspend fun connect() {
        val pending = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Main.immediate) {
                synchronized(lock) {
                    checkAvailable()
                    if (client != null || connectPending != null) throw busy()
                    connectPending = pending
                    try {
                        // This handler queues callbacks until connectGatt returns and the handle is stored.
                        @Suppress("DEPRECATION")
                        val gatt = device.connectGatt(
                            context,
                            false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK,
                            Handler(Looper.getMainLooper()),
                        )
                            ?: throw GattException(GattFailureKind.TRANSIENT, "无法创建蓝牙连接")
                        client = gatt
                    } catch (error: Exception) {
                        connectPending = null
                        throw mapException("无法创建蓝牙连接", error)
                    }
                }
            }
            pending.await()
        } catch (error: CancellationException) {
            close()
            throw error
        }
    }

    override suspend fun discoverServices(): List<GattService> {
        val pending = CompletableDeferred<List<GattService>>()
        synchronized(lock) {
            val gatt = requireClient()
            if (discoveryPending != null) throw busy()
            discoveryPending = pending
            try {
                if (!gatt.discoverServices()) throw initiationFailure("无法开始蓝牙服务发现")
            } catch (error: Exception) {
                discoveryPending = null
                throw mapException("无法开始蓝牙服务发现", error)
            }
        }
        return pending.await()
    }

    override suspend fun requestMtu(preferred: Int): Int? {
        val pending = CompletableDeferred<Int?>()
        synchronized(lock) {
            val gatt = requireClient()
            if (mtuPending != null) throw busy()
            mtuPending = pending
            try {
                if (!gatt.requestMtu(preferred)) {
                    mtuPending = null
                    return null
                }
            } catch (error: Exception) {
                mtuPending = null
                throw mapException("无法开始蓝牙 MTU 协商", error)
            }
        }
        return pending.await()
    }

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) {
        synchronized(lock) {
            val gatt = requireClient()
            val native = requireCharacteristic(characteristic)
            try {
                if (!gatt.setCharacteristicNotification(native, enabled)) throw initiationFailure("无法设置本地通知")
            } catch (error: Exception) {
                throw mapException("无法设置本地通知", error)
            }
        }
    }

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) {
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            val gatt = requireClient()
            val native = requireCharacteristic(characteristic)
            val descriptor = native.getDescriptor(CCCD_UUID)
                ?: throw GattException(GattFailureKind.UNSUPPORTED, "通知配置描述符缺失")
            if (descriptorPending != null) throw busy()
            val value = when (subscription) {
                GattSubscription.DISABLED -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                GattSubscription.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                GattSubscription.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            descriptorPending = PendingDescriptor(descriptor, completion)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    requireStarted(gatt.writeDescriptor(descriptor, value), "通知配置失败")
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    if (!gatt.writeDescriptor(descriptor)) throw initiationFailure("无法开始通知配置")
                }
            } catch (error: Exception) {
                descriptorPending = null
                throw mapException("无法开始通知配置", error)
            }
        }
        completion.await()
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            val gatt = requireClient()
            val native = requireCharacteristic(characteristic)
            if (writePending != null) throw busy()
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writePending = PendingWrite(native, completion)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    requireStarted(gatt.writeCharacteristic(native, value, type), "蓝牙写入失败")
                } else {
                    @Suppress("DEPRECATION")
                    native.value = value.copyOf()
                    native.writeType = type
                    @Suppress("DEPRECATION")
                    if (!gatt.writeCharacteristic(native)) throw initiationFailure("无法开始蓝牙写入")
                }
            } catch (error: Exception) {
                writePending = null
                throw mapException("无法开始蓝牙写入", error)
            }
        }
        completion.await()
    }

    override suspend fun close() {
        synchronized(lock) {
            terminate(GattException(GattFailureKind.DISCONNECTED, "蓝牙连接已关闭"), notify = false)
            releaseClient()
        }
    }

    private fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        synchronized(lock) {
            val shared = if (current(gatt)) sharedCharacteristics[characteristic] else null
            if (shared != null) {
                if (eventChannel.trySend(GattEvent.Notification(shared, value.copyOf())).isFailure) {
                    terminateFromCallback(eventOverflow(), notify = false, closeWithCause = true)
                }
            }
        }
    }

    private fun terminate(error: GattException, notify: Boolean, closeWithCause: Boolean = false) {
        if (closed) return
        closed = true
        failure = error
        characteristics.clear()
        sharedCharacteristics.clear()
        connectPending?.completeExceptionally(error)
        discoveryPending?.completeExceptionally(error)
        mtuPending?.completeExceptionally(error)
        descriptorPending?.completion?.completeExceptionally(error)
        writePending?.completion?.completeExceptionally(error)
        connectPending = null
        discoveryPending = null
        mtuPending = null
        descriptorPending = null
        writePending = null
        val delivered = !notify || eventChannel.trySend(GattEvent.Disconnected(error)).isSuccess
        eventChannel.close(if (closeWithCause || !delivered) error else null)
    }

    private fun terminateFromCallback(error: GattException, notify: Boolean, closeWithCause: Boolean = false) {
        terminate(error, notify, closeWithCause)
        // Binder callbacks cannot report a release failure to the caller. Keep the handle so
        // explicit close can retry and report the failure if it still cannot be released.
        runCatching { releaseClient() }
    }

    private fun releaseClient() {
        val old = client ?: return
        runCatching { old.disconnect() }
        try {
            old.close()
            client = null
            releaseFailure = null
        } catch (error: Exception) {
            val failure = mapException("无法释放蓝牙连接", error)
            releaseFailure?.let { failure.addSuppressed(it) }
            releaseFailure = failure
            throw failure
        }
    }

    private fun eventOverflow() = GattException(GattFailureKind.TRANSIENT, "蓝牙事件缓冲区已满")

    private fun current(gatt: BluetoothGatt) = !closed && client === gatt
    private fun checkAvailable() {
        if (closed) throw failure ?: GattException(GattFailureKind.DISCONNECTED, "蓝牙连接已关闭")
    }
    private fun requireClient(): BluetoothGatt {
        checkAvailable()
        return client ?: throw GattException(GattFailureKind.DISCONNECTED, "蓝牙连接不可用")
    }

    private fun connectionFailure(status: Int): GattException {
        val kind = when (status) {
            8, 62, 133, BluetoothGatt.GATT_FAILURE -> GattFailureKind.TRANSIENT
            5, 6, 15 -> GattFailureKind.PERMISSION
            19, 22 -> GattFailureKind.DISCONNECTED
            else -> GattFailureKind.UNKNOWN
        }
        return GattException(kind, "蓝牙连接失败（$status）")
    }
    private fun requireCharacteristic(characteristic: GattCharacteristic): BluetoothGattCharacteristic = characteristics[characteristic]
        ?: throw GattException(GattFailureKind.UNSUPPORTED, "蓝牙特征不可用")

    // Android callbacks have no operation token. A cancelled operation keeps its slot until its
    // callback arrives, so that callback cannot be mistaken for a subsequent operation.
    private fun busy() = GattException(GattFailureKind.TRANSIENT, "已有同类蓝牙操作等待回调")
    private fun initiationFailure(message: String) = GattException(GattFailureKind.TRANSIENT, message)

    private fun requireStarted(status: Int, message: String) {
        if (status == BluetoothStatusCodes.SUCCESS) return
        val kind = when (status) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> GattFailureKind.PERMISSION

            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> GattFailureKind.DISCONNECTED

            BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED -> GattFailureKind.UNSUPPORTED

            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND,
            -> GattFailureKind.TRANSIENT

            else -> GattFailureKind.UNKNOWN
        }
        throw GattException(kind, "$message（Android 状态码 $status）")
    }

    private fun callbackFailure(message: String, status: Int): GattException {
        val kind = when (status) {
            BluetoothGatt.GATT_READ_NOT_PERMITTED, BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
            -> GattFailureKind.UNSUPPORTED

            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, BluetoothGatt.GATT_INVALID_OFFSET -> GattFailureKind.INVALID_VALUE

            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
            -> GattFailureKind.PERMISSION

            BluetoothGatt.GATT_FAILURE, 133 -> GattFailureKind.TRANSIENT

            else -> GattFailureKind.UNKNOWN
        }
        return GattException(kind, "$message（$status）")
    }

    private fun mapException(message: String, error: Exception): GattException = when (error) {
        is GattException -> error
        is SecurityException -> GattException(GattFailureKind.PERMISSION, message, error)
        is IllegalArgumentException -> GattException(GattFailureKind.INVALID_VALUE, message, error)
        is UnsupportedOperationException -> GattException(GattFailureKind.UNSUPPORTED, message, error)
        else -> GattException(GattFailureKind.UNKNOWN, message, error)
    }

    private data class PendingDescriptor(val descriptor: BluetoothGattDescriptor, val completion: CompletableDeferred<Unit>)
    private data class PendingWrite(val characteristic: BluetoothGattCharacteristic, val completion: CompletableDeferred<Unit>)

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

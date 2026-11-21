package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
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
import java.util.UUID

/** Android's native GATT operations; connection policy is owned by the shared layer. */
@SuppressLint("MissingPermission")
internal class AndroidGattDriver(
    context: Context,
    device: BluetoothDevice,
) : GattDriver {
    private val lock = Any()
    private val eventChannel = Channel<GattEvent>(Channel.BUFFERED)
    override val events = eventChannel.receiveAsFlow()
    private val handle = AndroidGattClient(context, device)
    private var connectPending: CompletableDeferred<Unit>? = null
    private var discoveryPending: CompletableDeferred<List<GattService>>? = null
    private var mtuPending: CompletableDeferred<Int?>? = null
    private var descriptorPending: PendingDescriptor? = null
    private var writePending: PendingWrite? = null
    private val services = AndroidGattServices()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                if (!handle.matches(gatt)) return
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
                if (!handle.matches(gatt)) return
                val pending = discoveryPending ?: return
                discoveryPending = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        pending.complete(androidGattCall("读取蓝牙服务表失败") { services.readServices(gatt) })
                    } catch (error: GattException) {
                        pending.completeExceptionally(error)
                    }
                } else {
                    pending.completeExceptionally(callbackFailure("蓝牙服务发现失败", status))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(lock) {
                if (!handle.matches(gatt)) return
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
                val pending = descriptorPending ?: return
                if (!handle.matches(gatt) || pending.descriptor !== descriptor) return
                descriptorPending = null
                pending.completion.completeWrite(status, "通知配置失败")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(lock) {
                val pending = writePending ?: return
                if (!handle.matches(gatt) || pending.characteristic !== characteristic) return
                writePending = null
                pending.completion.completeWrite(status, "蓝牙写入失败")
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
                if (!handle.matches(gatt)) return
                services.clear()
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
                    handle.open(callback)
                    connectPending = pending
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
            val gatt = handle.requireOpen()
            requireIdle(discoveryPending == null)
            androidGattCall("无法开始蓝牙服务发现") {
                requireStarted(gatt.discoverServices(), "无法开始蓝牙服务发现")
            }
            discoveryPending = pending
        }
        return pending.await()
    }

    override suspend fun requestMtu(preferred: Int): Int? {
        val pending = CompletableDeferred<Int?>()
        synchronized(lock) {
            val gatt = handle.requireOpen()
            requireIdle(mtuPending == null)
            if (!androidGattCall("无法开始蓝牙 MTU 协商") { gatt.requestMtu(preferred) }) return null
            mtuPending = pending
        }
        return pending.await()
    }

    override suspend fun setNotificationRouting(characteristic: GattCharacteristic, enabled: Boolean) {
        synchronized(lock) {
            val gatt = handle.requireOpen()
            val native = services.requireCharacteristic(characteristic)
            androidGattCall("无法设置本地通知") {
                requireStarted(gatt.setCharacteristicNotification(native, enabled), "无法设置本地通知")
            }
        }
    }

    override suspend fun configureSubscription(characteristic: GattCharacteristic, subscription: GattSubscription) {
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            val gatt = handle.requireOpen()
            val native = services.requireCharacteristic(characteristic)
            val descriptor = native.getDescriptor(CCCD_UUID)
                ?: throw GattException(GattFailureKind.UNSUPPORTED, "通知配置描述符缺失")
            requireIdle(descriptorPending == null)
            val value = when (subscription) {
                GattSubscription.DISABLED -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                GattSubscription.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                GattSubscription.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            androidGattCall("无法开始通知配置") {
                if (Build.VERSION.SDK_INT >= 33) {
                    requireStarted(gatt.writeDescriptor(descriptor, value), "通知配置失败")
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    requireStarted(gatt.writeDescriptor(descriptor), "无法开始通知配置")
                }
            }
            descriptorPending = PendingDescriptor(descriptor, completion)
        }
        completion.await()
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray, withResponse: Boolean) {
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            val gatt = handle.requireOpen()
            val native = services.requireCharacteristic(characteristic)
            requireIdle(writePending == null)
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            androidGattCall("无法开始蓝牙写入") {
                if (Build.VERSION.SDK_INT >= 33) {
                    requireStarted(gatt.writeCharacteristic(native, value, type), "蓝牙写入失败")
                } else {
                    @Suppress("DEPRECATION")
                    native.value = value.copyOf()
                    native.writeType = type
                    @Suppress("DEPRECATION")
                    requireStarted(gatt.writeCharacteristic(native), "无法开始蓝牙写入")
                }
            }
            writePending = PendingWrite(native, completion)
        }
        completion.await()
    }

    override suspend fun close() {
        synchronized(lock) {
            terminate(GattException(GattFailureKind.DISCONNECTED, "蓝牙连接已关闭"), notify = false)
            handle.release()
        }
    }

    private fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        synchronized(lock) {
            val shared = if (handle.matches(gatt)) services.shared(characteristic) else null
            if (shared != null) {
                if (eventChannel.trySend(GattEvent.Notification(shared, value.copyOf())).isFailure) {
                    terminateFromCallback(eventOverflow(), notify = false, closeWithCause = true)
                }
            }
        }
    }

    private fun terminate(error: GattException, notify: Boolean, closeWithCause: Boolean = false) {
        if (!handle.end(error)) return
        services.clear()
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
        runCatching { handle.release() }
    }

    private fun eventOverflow() = GattException(GattFailureKind.TRANSIENT, "蓝牙事件缓冲区已满")

    private data class PendingDescriptor(val descriptor: BluetoothGattDescriptor, val completion: CompletableDeferred<Unit>)
    private data class PendingWrite(val characteristic: BluetoothGattCharacteristic, val completion: CompletableDeferred<Unit>)

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

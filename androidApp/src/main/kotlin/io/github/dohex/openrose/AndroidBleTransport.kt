package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.RecoverableTransportException
import io.github.dohex.openrose.core.transport.TerminalTransportException
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportRecoveryReason
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Native Android BLE only; shared session/protocol code has no Android API dependency. */
@SuppressLint("MissingPermission") // The public session gates permission; revocation remains an observable failure.
internal class AndroidBleTransport(private val context: Context) : ControlTransport {
    private fun adapter(): BluetoothAdapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("系统蓝牙适配器不可用")

    override fun scan(): Flow<Advertisement> = callbackFlow {
        requireBluetoothPermission(scan = true)
        val scanner = adapter().bluetoothLeScanner ?: throw IOException("系统蓝牙未开启或扫描不可用")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val manufacturer = record?.manufacturerSpecificData
                val data = buildMap {
                    if (manufacturer != null) {
                        for (index in 0 until manufacturer.size()) {
                            put(manufacturer.keyAt(index), manufacturer.valueAt(index).copyOf())
                        }
                    }
                }
                trySend(Advertisement(
                    deviceId = DeviceId(result.device.address),
                    name = record?.deviceName,
                    rssi = result.rssi,
                    manufacturerData = data,
                ))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("蓝牙扫描失败（$errorCode）"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    override suspend fun connect(
        deviceId: DeviceId,
        endpoint: TransportEndpoint,
        onPhase: (ConnectionPhase) -> Unit,
    ): ControlConnection {
        val gattEndpoint = endpoint as? TransportEndpoint.BleGatt
            ?: throw IOException("Android 尚未实现该传输类型")
        requireBluetoothPermission(scan = false)
        val connection = AndroidGattConnection(context, adapter().getRemoteDevice(deviceId.value), gattEndpoint)
        try {
            connection.open(onPhase)
            return connection
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun requireBluetoothPermission(scan: Boolean) {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (scan) {
                listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (scan) {
            listOf(android.Manifest.permission.BLUETOOTH, android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(android.Manifest.permission.BLUETOOTH)
        }
        if (required.any { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            throw SecurityException("缺少 BLE ${if (scan) "扫描" else "连接"}权限")
        }
    }
}

@SuppressLint("MissingPermission")
private class AndroidGattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val endpoint: TransportEndpoint.BleGatt,
) : ControlConnection {
    private val received = Channel<ByteArray>(Channel.BUFFERED)
    override val notifications: Flow<ByteArray> = received.receiveAsFlow()
    @Volatile
    override var maxWriteSize: Int = 20
        private set

    private val stateLock = Any()
    private val gatt = AtomicReference<BluetoothGatt?>(null)
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val servicesReady = CompletableDeferred<Unit>()
    private val mtuReady = CompletableDeferred<Unit>()
    private val writeLock = Mutex()
    private val pendingDescriptor = AtomicReference<PendingDescriptor?>(null)
    private val pendingWrite = AtomicReference<PendingWrite?>(null)
    private val closed = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<Throwable?>(null)
    private var discoveryAttempts = 0
    private var discoveryInFlight = false
    private var serviceChangedDuringDiscovery = false
    private var initializationOperationStarted = false
    private var phaseListener: (ConnectionPhase) -> Unit = {}

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(client)) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val message = "蓝牙连接已断开（$status）"
                terminate(classifiedGattFailure(message, status) ?: TerminalTransportException(message))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                runCatching { phaseListener(ConnectionPhase.DISCOVERING) }
                requestDiscovery(client, TransportRecoveryReason.SERVICES_UNAVAILABLE)
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (!isCurrent(client)) return
            synchronized(stateLock) {
                if (!isCurrent(client) || !discoveryInFlight) return
                discoveryInFlight = false
                val attempt = discoveryAttempts
                val changedWhileDiscovering = serviceChangedDuringDiscovery
                serviceChangedDuringDiscovery = false
                clearDiscoveredReferences()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    terminate(callbackFailure(
                        TransportRecoveryReason.SERVICE_DISCOVERY,
                        "蓝牙服务发现失败（$status）",
                        status,
                    ))
                    return
                }
                if (changedWhileDiscovering) {
                    if (attempt < MAX_SERVICE_DISCOVERIES) {
                        requestDiscovery(client, TransportRecoveryReason.SERVICES_CHANGED)
                    } else {
                        terminate(recoverable(
                            TransportRecoveryReason.SERVICES_CHANGED,
                            "蓝牙服务在发现过程中发生变化",
                        ))
                    }
                    return
                }
                val service: BluetoothGattService?
                val writer: BluetoothGattCharacteristic?
                val notifier: BluetoothGattCharacteristic?
                try {
                    service = client.getService(UUID.fromString(endpoint.serviceUuid))
                    writer = service?.getCharacteristic(UUID.fromString(endpoint.writeUuid))
                    notifier = service?.getCharacteristic(UUID.fromString(endpoint.notifyUuid))
                } catch (error: SecurityException) {
                    terminate(error)
                    return
                } catch (error: IllegalArgumentException) {
                    terminate(error)
                    return
                } catch (error: Exception) {
                    terminate(recoverable(
                        TransportRecoveryReason.SERVICE_DISCOVERY,
                        "读取蓝牙服务表失败",
                        error,
                    ))
                    return
                }
                if (writer == null || notifier == null) {
                    if (attempt < MAX_SERVICE_DISCOVERIES) {
                        requestDiscovery(client, TransportRecoveryReason.SERVICES_UNAVAILABLE)
                    } else {
                        terminate(recoverable(
                            TransportRecoveryReason.SERVICES_UNAVAILABLE,
                            "重新发现后仍未找到此型号要求的服务或特征",
                        ))
                    }
                    return
                }
                val writable = writer.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                val notifiable = notifier.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (!writable || !notifiable) {
                    terminate(UnsupportedOperationException("设备特征不提供所需的写入或订阅能力"))
                    return
                }
                writeCharacteristic = writer
                notifyCharacteristic = notifier
                servicesReady.complete(Unit)
            }
        }

        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrent(client)) return
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) maxWriteSize = (mtu - 3).coerceAtMost(512)
            mtuReady.complete(Unit)
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrent(client)) return
            val pending = pendingDescriptor.get() ?: return
            if (descriptor !== pending.descriptor || descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (pendingDescriptor.compareAndSet(pending, null)) pending.completion.complete(Unit)
            } else if (pendingDescriptor.compareAndSet(pending, null)) {
                pending.completion.completeExceptionally(callbackFailure(
                    TransportRecoveryReason.SUBSCRIPTION,
                    "通知订阅失败（$status）",
                    status,
                ))
            }
        }

        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!isCurrent(client)) return
            val pending = pendingWrite.get() ?: return
            if (characteristic !== pending.characteristic) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (pendingWrite.compareAndSet(pending, null)) pending.completion.complete(Unit)
            } else if (pendingWrite.compareAndSet(pending, null)) {
                pending.completion.completeExceptionally(callbackFailure(
                    TransportRecoveryReason.WRITE_FAILED,
                    "蓝牙写入失败（$status）",
                    status,
                ))
            }
        }

        @Deprecated("Called by Android before API 33")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (isCurrent(client)) receive(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (isCurrent(client)) receive(characteristic, value)
        }

        override fun onServiceChanged(client: BluetoothGatt) {
            if (!isCurrent(client)) return
            val shouldTerminate: Boolean
            val shouldRediscover: Boolean
            synchronized(stateLock) {
                clearDiscoveredReferences()
                shouldTerminate = servicesReady.isCompleted || initializationOperationStarted
                shouldRediscover = !shouldTerminate && !discoveryInFlight
                if (!shouldTerminate && discoveryInFlight) serviceChangedDuringDiscovery = true
            }
            if (shouldTerminate) {
                terminate(recoverable(
                    TransportRecoveryReason.SERVICES_CHANGED,
                    "蓝牙服务数据库已变化；需要重新建立连接",
                ))
            } else if (shouldRediscover) {
                requestDiscovery(client, TransportRecoveryReason.SERVICES_CHANGED)
            }
        }
    }

    suspend fun open(onPhase: (ConnectionPhase) -> Unit) {
        phaseListener = onPhase
        withContext(Dispatchers.Main.immediate) {
            // Assign inside the dispatcher block so cancellation cannot discard an opened handle.
            @Suppress("DEPRECATION") // The API 26 Handler overload queues callbacks until the GATT handle is registered.
            val client = device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                Handler(Looper.getMainLooper()),
            ) ?: throw IOException("无法创建蓝牙连接")
            val accepted = synchronized(stateLock) {
                !closed.get() && gatt.compareAndSet(null, client)
            }
            if (!accepted) {
                runCatching { client.disconnect() }
                runCatching { client.close() }
                throw IOException("蓝牙连接已关闭")
            }
        }
        servicesReady.await()
        // A failed MTU negotiation keeps the safe default of 20 bytes; codecs generate whole queries for it.
        val client = gatt.get() ?: unavailable("蓝牙连接不可用")
        if (client.requestMtu(185)) withTimeoutOrNull(1500) { mtuReady.await() }
    }

    override suspend fun subscribe() = writeLock.withLock {
        checkOpen()
        val client = gatt.get() ?: unavailable("蓝牙连接不可用")
        val characteristic = notifyCharacteristic ?: unavailable("通知特征不可用")
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: throw UnsupportedOperationException("通知配置描述符缺失")
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val completion = CompletableDeferred<Unit>()
        val pending = PendingDescriptor(descriptor, completion)
        if (!pendingDescriptor.compareAndSet(null, pending)) throw IOException("已有通知订阅正在等待")
        synchronized(stateLock) { initializationOperationStarted = true }
        var localNotificationAttempted = false
        try {
            localNotificationAttempted = true
            if (!client.setCharacteristicNotification(characteristic, true)) {
                throw recoverable(TransportRecoveryReason.SUBSCRIPTION, "无法启用本地通知接收")
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                requireWriteInitiated(
                    client.writeDescriptor(descriptor, value),
                    TransportRecoveryReason.SUBSCRIPTION,
                    "通知订阅失败",
                )
                true
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                client.writeDescriptor(descriptor)
            }
            if (!started) throw recoverable(TransportRecoveryReason.SUBSCRIPTION, "无法开始通知订阅")
            val completed = withTimeoutOrNull(NATIVE_OPERATION_TIMEOUT_MS) {
                completion.await()
                true
            } ?: throw recoverable(TransportRecoveryReason.SUBSCRIPTION, "通知订阅超时")
            check(completed)
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (localNotificationAttempted && isCurrent(client)) runCatching { client.setCharacteristicNotification(characteristic, false) }
            throw error
        } catch (error: Exception) {
            if (localNotificationAttempted && isCurrent(client)) runCatching { client.setCharacteristicNotification(characteristic, false) }
            val mapped = mapOperationFailure(TransportRecoveryReason.SUBSCRIPTION, "通知订阅失败", error)
            terminate(mapped)
            throw mapped
        } finally {
            pendingDescriptor.compareAndSet(pending, null)
        }
    }

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        checkOpen()
        require(bytes.isNotEmpty() && bytes.size <= maxWriteSize) { "协议报文必须完整适配当前写入容量" }
        val client = gatt.get() ?: unavailable("蓝牙连接不可用")
        val characteristic = writeCharacteristic ?: unavailable("写入特征不可用")
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val completion = CompletableDeferred<Unit>()
        val pending = PendingWrite(characteristic, completion)
        if (!pendingWrite.compareAndSet(null, pending)) throw IOException("已有蓝牙写入正在等待")
        synchronized(stateLock) { initializationOperationStarted = true }
        try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                requireWriteInitiated(
                    client.writeCharacteristic(characteristic, bytes, type),
                    TransportRecoveryReason.WRITE_FAILED,
                    "蓝牙写入失败",
                )
                true
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes.copyOf()
                characteristic.writeType = type
                @Suppress("DEPRECATION")
                client.writeCharacteristic(characteristic)
            }
            if (!started) throw recoverable(TransportRecoveryReason.WRITE_FAILED, "无法开始蓝牙写入")
            val completed = withTimeoutOrNull(NATIVE_OPERATION_TIMEOUT_MS) {
                completion.await()
                true
            } ?: throw recoverable(TransportRecoveryReason.WRITE_FAILED, "蓝牙写入超时")
            check(completed)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            val mapped = mapOperationFailure(TransportRecoveryReason.WRITE_FAILED, "蓝牙写入失败", error)
            terminate(mapped)
            throw mapped
        } finally {
            pendingWrite.compareAndSet(pending, null)
        }
    }

    override suspend fun close() {
        terminate(IOException("蓝牙连接已关闭"), closeNotificationsWithCause = false)
    }

    private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (closed.get() || characteristic !== notifyCharacteristic) return
        if (received.trySend(value.copyOf()).isFailure) fail(IOException("通知接收缓冲区已满或已关闭"))
    }

    private fun checkOpen() {
        if (closed.get()) unavailable("蓝牙连接已关闭")
    }

    private fun requestDiscovery(client: BluetoothGatt, exhaustedReason: TransportRecoveryReason) {
        val attempt = synchronized(stateLock) {
            if (!isCurrent(client) || discoveryInFlight) return
            if (discoveryAttempts >= MAX_SERVICE_DISCOVERIES) return@synchronized null
            clearDiscoveredReferences()
            discoveryInFlight = true
            ++discoveryAttempts
        }
        if (attempt == null) {
            val message = if (exhaustedReason == TransportRecoveryReason.SERVICES_CHANGED) {
                "服务变化后无法再次发现"
            } else {
                "服务发现次数已用尽"
            }
            terminate(recoverable(exhaustedReason, message))
            return
        }
        try {
            if (!client.discoverServices()) {
                synchronized(stateLock) { discoveryInFlight = false }
                terminate(recoverable(
                    TransportRecoveryReason.SERVICE_DISCOVERY,
                    "无法开始蓝牙服务发现",
                ))
            }
        } catch (error: SecurityException) {
            synchronized(stateLock) { discoveryInFlight = false }
            terminate(error)
        } catch (error: IllegalArgumentException) {
            synchronized(stateLock) { discoveryInFlight = false }
            terminate(error)
        } catch (error: Exception) {
            synchronized(stateLock) { discoveryInFlight = false }
            terminate(recoverable(
                TransportRecoveryReason.SERVICE_DISCOVERY,
                "无法开始蓝牙服务发现",
                error,
            ))
        }
    }

    private fun isCurrent(client: BluetoothGatt): Boolean = !closed.get() && gatt.get() === client

    private fun unavailable(message: String): Nothing {
        throw terminalFailure.get() ?: IOException(message)
    }

    private fun clearDiscoveredReferences() {
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private fun fail(error: IOException) = terminate(error)

    private fun terminate(error: Throwable, closeNotificationsWithCause: Boolean = true) {
        val client = synchronized(stateLock) {
            if (closed.get()) return
            terminalFailure.set(error)
            closed.set(true)
            clearDiscoveredReferences()
            discoveryInFlight = false
            serviceChangedDuringDiscovery = false
            gatt.getAndSet(null)
        }
        servicesReady.completeExceptionally(error)
        mtuReady.completeExceptionally(error)
        pendingDescriptor.getAndSet(null)?.completion?.completeExceptionally(error)
        pendingWrite.getAndSet(null)?.completion?.completeExceptionally(error)
        if (closeNotificationsWithCause) received.close(error) else received.close()
        client?.let { client ->
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
    }

    private fun mapOperationFailure(
        reason: TransportRecoveryReason,
        message: String,
        error: Throwable,
    ): Throwable = when (error) {
        is RecoverableTransportException,
        is TerminalTransportException,
        is SecurityException,
        is IllegalArgumentException,
        is UnsupportedOperationException -> error
        else -> recoverable(reason, message, error)
    }

    private fun requireWriteInitiated(
        status: Int,
        reason: TransportRecoveryReason,
        message: String,
    ) {
        if (status == BluetoothStatusCodes.SUCCESS) return
        val failure = when (status) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION ->
                SecurityException("缺少蓝牙连接权限（$message）")
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED ->
                TerminalTransportException("蓝牙未开启（$message）")
            BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED ->
                UnsupportedOperationException("远端设备不允许此 GATT 写入（$message）")
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND ->
                recoverable(reason, "$message（Android 状态码 $status）")
            else -> TerminalTransportException("$message（未知 Android 状态码 $status）")
        }
        throw failure
    }

    private fun callbackFailure(
        reason: TransportRecoveryReason,
        message: String,
        status: Int,
    ): Exception = classifiedGattFailure(message, status) ?: recoverable(reason, message)

    private fun classifiedGattFailure(message: String, status: Int): Exception? = when (status) {
        BluetoothGatt.GATT_READ_NOT_PERMITTED,
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED ->
            UnsupportedOperationException("远端 GATT 不允许此请求（$message）")
        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
        BluetoothGatt.GATT_INVALID_OFFSET ->
            IllegalArgumentException("远端 GATT 拒绝了无效长度或偏移（$message）")
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
        BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION,
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
            SecurityException("远端 GATT 要求额外认证或加密（$message）")
        else -> null
    }

    private fun recoverable(
        reason: TransportRecoveryReason,
        message: String,
        cause: Throwable? = null,
    ) = RecoverableTransportException(reason, message, cause)

    private data class PendingDescriptor(
        val descriptor: BluetoothGattDescriptor,
        val completion: CompletableDeferred<Unit>,
    )

    private data class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val completion: CompletableDeferred<Unit>,
    )

    private companion object {
        const val MAX_SERVICE_DISCOVERIES = 2
        const val NATIVE_OPERATION_TIMEOUT_MS = 5_000L
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

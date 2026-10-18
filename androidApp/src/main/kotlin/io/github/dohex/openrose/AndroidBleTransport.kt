package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import java.io.IOException
import java.util.UUID
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Native Android BLE only; shared session/protocol code has no Android API dependency. */
@SuppressLint("MissingPermission") // The public session gates permission; revocation remains an observable failure.
internal class AndroidBleTransport(private val context: Context) : ControlTransport {
    private fun adapter(): BluetoothAdapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("系统蓝牙适配器不可用")

    override fun scan(): Flow<Advertisement> = callbackFlow {
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
        val connection = AndroidGattConnection(context, adapter().getRemoteDevice(deviceId.value), gattEndpoint)
        try {
            connection.open(onPhase)
            return connection
        } catch (error: Throwable) {
            connection.close()
            throw error
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
    override var maxWriteSize: Int = 20
        private set

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val servicesReady = CompletableDeferred<Unit>()
    private val mtuReady = CompletableDeferred<Unit>()
    private val writeLock = Mutex()
    @Volatile private var pendingDescriptor: CompletableDeferred<Unit>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile private var closed = false
    private var phaseListener: (ConnectionPhase) -> Unit = {}

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(IOException("蓝牙连接已断开（$status）"))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                phaseListener(ConnectionPhase.DISCOVERING)
                if (!client.discoverServices()) fail(IOException("无法开始蓝牙服务发现"))
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IOException("蓝牙服务发现失败（$status）"))
                return
            }
            val service = client.getService(UUID.fromString(endpoint.serviceUuid))
            val writer = service?.getCharacteristic(UUID.fromString(endpoint.writeUuid))
            val notifier = service?.getCharacteristic(UUID.fromString(endpoint.notifyUuid))
            if (writer == null || notifier == null) {
                fail(IOException("未发现此型号要求的服务或特征；不会尝试其他型号命令"))
                return
            }
            val writable = writer.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val notifiable = notifier.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            if (!writable || !notifiable) {
                fail(IOException("设备特征不提供所需的写入或订阅能力"))
                return
            }
            writeCharacteristic = writer
            notifyCharacteristic = notifier
            servicesReady.complete(Unit)
        }

        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) maxWriteSize = (mtu - 3).coerceAtMost(512)
            mtuReady.complete(Unit)
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed || descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) pendingDescriptor?.complete(Unit)
            else pendingDescriptor?.completeExceptionally(IOException("通知订阅失败（$status）"))
        }

        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (closed || characteristic.uuid != writeCharacteristic?.uuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else pendingWrite?.completeExceptionally(IOException("蓝牙写入失败（$status）"))
        }

        @Deprecated("Called by Android before API 33")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            receive(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(characteristic, value)
        }
    }

    suspend fun open(onPhase: (ConnectionPhase) -> Unit) {
        phaseListener = onPhase
        withContext(Dispatchers.Main.immediate) {
            // Assign inside the dispatcher block so cancellation cannot discard an opened handle.
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("无法创建蓝牙连接")
        }
        servicesReady.await()
        // A failed MTU negotiation keeps the safe default of 20 bytes; codecs generate whole queries for it.
        if (gatt?.requestMtu(185) == true) withTimeoutOrNull(1500) { mtuReady.await() }
    }

    override suspend fun subscribe() = writeLock.withLock {
        checkOpen()
        val client = gatt ?: throw IOException("蓝牙连接不可用")
        val characteristic = notifyCharacteristic ?: throw IOException("通知特征不可用")
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: throw IOException("通知配置描述符缺失")
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val completion = CompletableDeferred<Unit>()
        pendingDescriptor = completion
        try {
            if (!client.setCharacteristicNotification(characteristic, true)) throw IOException("无法启用本地通知接收")
            val started = if (Build.VERSION.SDK_INT >= 33) client.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                client.writeDescriptor(descriptor)
            }
            if (!started) throw IOException("无法开始通知订阅")
            withTimeout(5000) { completion.await() }
        } finally {
            pendingDescriptor = null
        }
    }

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        checkOpen()
        require(bytes.isNotEmpty() && bytes.size <= maxWriteSize) { "协议报文必须完整适配当前写入容量" }
        val client = gatt ?: throw IOException("蓝牙连接不可用")
        val characteristic = writeCharacteristic ?: throw IOException("写入特征不可用")
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val completion = CompletableDeferred<Unit>()
        pendingWrite = completion
        try {
            val started = if (Build.VERSION.SDK_INT >= 33) client.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes.copyOf()
                characteristic.writeType = type
                @Suppress("DEPRECATION")
                client.writeCharacteristic(characteristic)
            }
            if (!started) throw IOException("无法开始蓝牙写入")
            withTimeout(5000) { completion.await() }
        } finally {
            pendingWrite = null
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        val error = IOException("蓝牙连接已关闭")
        servicesReady.completeExceptionally(error)
        mtuReady.completeExceptionally(error)
        pendingDescriptor?.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        received.close()
        gatt?.let { client ->
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
        gatt = null
    }

    private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (closed || characteristic.uuid != notifyCharacteristic?.uuid) return
        if (received.trySend(value.copyOf()).isFailure) fail(IOException("通知接收缓冲区已满或已关闭"))
    }

    private fun checkOpen() {
        if (closed) throw IOException("蓝牙连接已关闭")
    }

    private fun fail(error: IOException) {
        if (closed) return
        servicesReady.completeExceptionally(error)
        mtuReady.completeExceptionally(error)
        pendingDescriptor?.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        received.close(error)
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

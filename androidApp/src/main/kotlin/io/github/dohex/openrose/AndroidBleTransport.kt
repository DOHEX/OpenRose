package io.github.dohex.openrose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import io.github.dohex.openrose.core.transport.GattControlConnection
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/** Native Android BLE only; shared session/protocol code has no Android API dependency. */
@SuppressLint("MissingPermission") // The public session gates permission; revocation remains an observable failure.
internal class AndroidBleTransport(private val context: Context) : ControlTransport {
    private fun adapter(): BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
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
                trySend(
                    Advertisement(
                        deviceId = DeviceId(result.device.address),
                        name = record?.deviceName,
                        rssi = result.rssi,
                        manufacturerData = data,
                    ),
                )
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
        return GattControlConnection.open(
            AndroidGattDriver(context, adapter().getRemoteDevice(deviceId.value)),
            gattEndpoint,
            onPhase,
        )
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

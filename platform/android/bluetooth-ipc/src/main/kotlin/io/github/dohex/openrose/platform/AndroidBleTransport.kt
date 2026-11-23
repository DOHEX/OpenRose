package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.GattControlConnection
import io.github.dohex.openrose.core.transport.TransportEndpoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/** Native Android BLE scanning and GATT links; shared session/protocol code has no Android API dependency. */
@SuppressLint("MissingPermission") // The public session gates permission; revocation remains an observable failure.
internal class AndroidBleTransport(private val context: Context) {
    private fun adapter(): BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw IOException("系统蓝牙适配器不可用")

    fun scan(): Flow<Advertisement> = callbackFlow {
        requireBluetoothPermission(scan = true)
        if (Build.VERSION.SDK_INT <= 30 && !locationServicesEnabled()) {
            throw IOException("请在系统设置中开启定位服务，以便此 Android 版本扫描蓝牙设备")
        }
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

    suspend fun connect(
        deviceId: DeviceId,
        endpoint: TransportEndpoint.BleGatt,
        onPhase: (ConnectionPhase) -> Unit,
    ): ControlConnection {
        requireBluetoothPermission(scan = false)
        return GattControlConnection.open(
            AndroidGattDriver(context, adapter().getRemoteDevice(deviceId.value)),
            endpoint,
            onPhase,
        )
    }

    private fun locationServicesEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
            Settings.Secure.LOCATION_MODE_OFF
    }

    private fun requireBluetoothPermission(scan: Boolean) {
        val scanGranted = !scan || bluetoothPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!scanGranted || !context.hasBluetoothConnectPermission()) {
            throw SecurityException("缺少 BLE ${if (scan) "扫描" else "连接"}权限")
        }
    }
}

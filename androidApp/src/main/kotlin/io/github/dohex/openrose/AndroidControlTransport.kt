package io.github.dohex.openrose

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dohex.openrose.core.ConnectionPhase
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.ControlConnection
import io.github.dohex.openrose.core.transport.ControlTransport
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportEndpoint
import io.github.dohex.openrose.core.transport.TransportFacts
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/** Routes one session over BLE or RFCOMM while preserving the shared session's single-owner model. */
internal class AndroidControlTransport(context: Context) : ControlTransport {
    private val ble = AndroidBleTransport(context)
    private val audioProfiles = AndroidAudioProfiles(context)
    private val appContext = context.applicationContext

    override val supportsInitializationRecovery: Boolean = true

    override fun scan(): Flow<Advertisement> = ble.scan()

    override suspend fun connectionFacts(deviceId: DeviceId, classicAddress: String?): TransportFacts =
        audioProfiles.connectionFacts(deviceId, classicAddress)

    @SuppressLint("MissingPermission") // SPP verifies runtime permission before accessing adapter state.
    override suspend fun connect(
        deviceId: DeviceId,
        endpoint: TransportEndpoint,
        onPhase: (ConnectionPhase) -> Unit,
    ): ControlConnection = when (endpoint) {
        is TransportEndpoint.BleGatt -> ble.connect(deviceId, endpoint, onPhase)
        is TransportEndpoint.SppRfcomm -> {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("系统蓝牙适配器不可用")
            val address = normalizeAddress(endpoint.address)
                ?: throw IOException("SPP 设备地址无效")
            if (!hasBluetoothPermission()) throw IOException("缺少蓝牙连接权限")
            val enabled = try {
                adapter.isEnabled
            } catch (error: SecurityException) {
                throw IOException("无法读取蓝牙适配器状态", error)
            }
            if (!enabled || !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
                throw IOException("此设备当前无法使用经典蓝牙 SPP")
            }
            val connection = AndroidSppConnection(
                adapter = adapter,
                address = address,
                serviceUuid = endpoint.serviceUuid,
            )
            try {
                connection.open(onPhase)
                connection
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }
    }

    private companion object {
        fun hasBluetoothPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        fun normalizeAddress(value: String): String? {
            val candidate = value.trim().uppercase(Locale.ROOT)
            return candidate.takeIf(BluetoothAdapter::checkBluetoothAddress)
        }
    }

    private fun hasBluetoothPermission(): Boolean = hasBluetoothPermission(appContext)
}

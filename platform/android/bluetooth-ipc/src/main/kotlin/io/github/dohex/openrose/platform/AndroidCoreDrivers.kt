package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.dohex.openrose.core.transport.Advertisement
import io.github.dohex.openrose.core.transport.AudioFactsDriver
import io.github.dohex.openrose.core.transport.GattDriver
import io.github.dohex.openrose.ffi.drivers.ControlTransportAdapter
import io.github.dohex.openrose.ffi.drivers.PlatformDrivers
import io.github.dohex.openrose.ffi.drivers.SppLink
import kotlinx.coroutines.flow.Flow

fun androidCoreTransport(context: Context): ControlTransportAdapter = ControlTransportAdapter(AndroidCoreDrivers(context))

@SuppressLint("MissingPermission")
internal class AndroidCoreDrivers(context: Context) : PlatformDrivers {
    private val appContext = context.applicationContext
    private val ble = AndroidBleTransport(appContext)

    override val supportsInitializationRecovery: Boolean = true

    override val audio: AudioFactsDriver = AndroidAudioProfiles(appContext)

    override fun gatt(address: String): GattDriver = ble.driver(address)

    override fun scan(): Flow<Advertisement> = ble.scan()

    override fun spp(address: String, serviceUuid: String): SppLink = AndroidSppConnection(classicAdapter(), address, serviceUuid)

    private fun classicAdapter(): BluetoothAdapter {
        if (!appContext.hasBluetoothConnectPermission()) throw SecurityException("缺少蓝牙连接权限")
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val usable = adapter != null && adapter.isEnabled && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        return adapter?.takeIf { usable } ?: throw UnsupportedOperationException("此设备当前无法使用经典蓝牙 SPP")
    }
}

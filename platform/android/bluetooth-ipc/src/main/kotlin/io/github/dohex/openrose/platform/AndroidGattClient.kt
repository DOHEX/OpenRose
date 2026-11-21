package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind

/** The driver holds its callback lock while accessing the native handle and retrying a failed release. */
@SuppressLint("MissingPermission")
internal class AndroidGattClient(private val context: Context, private val device: BluetoothDevice) {
    private var client: BluetoothGatt? = null
    private var ended: GattException? = null
    private var releaseFailure: GattException? = null

    fun open(callback: BluetoothGattCallback) {
        ended?.let { throw it }
        requireIdle(client == null)
        client = androidGattCall("无法创建蓝牙连接") {
            // Queue callbacks on the main handler until the native handle has been stored.
            @Suppress("DEPRECATION")
            device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                Handler(Looper.getMainLooper()),
            )
        } ?: throw GattException(GattFailureKind.TRANSIENT, "无法创建蓝牙连接")
    }

    fun matches(gatt: BluetoothGatt): Boolean = ended == null && client === gatt

    fun requireOpen(): BluetoothGatt {
        ended?.let { throw it }
        return client ?: throw GattException(GattFailureKind.DISCONNECTED, "蓝牙连接不可用")
    }

    fun end(error: GattException): Boolean {
        if (ended != null) return false
        ended = error
        return true
    }

    fun release() {
        val old = client ?: return
        runCatching { old.disconnect() }
        try {
            androidGattCall("无法释放蓝牙连接") { old.close() }
            client = null
            releaseFailure = null
        } catch (failure: GattException) {
            releaseFailure?.let { failure.addSuppressed(it) }
            releaseFailure = failure
            throw failure
        }
    }
}

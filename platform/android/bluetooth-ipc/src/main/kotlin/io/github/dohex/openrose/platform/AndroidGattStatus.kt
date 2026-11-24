package io.github.dohex.openrose.platform

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothStatusCodes
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import kotlinx.coroutines.CompletableDeferred

internal fun connectionFailure(status: Int): GattException {
    val kind = when (status) {
        8, 62, 133, BluetoothGatt.GATT_FAILURE -> GattFailureKind.TRANSIENT
        5, 6, 15 -> GattFailureKind.PERMISSION
        19, 22 -> GattFailureKind.DISCONNECTED
        else -> GattFailureKind.UNKNOWN
    }
    return GattException(kind, "蓝牙连接失败（$status）")
}

internal fun requireIdle(idle: Boolean) {
    if (!idle) throw GattException(GattFailureKind.TRANSIENT, "已有同类蓝牙操作等待回调")
}

internal fun requireStarted(started: Boolean, message: String) {
    if (!started) throw GattException(GattFailureKind.TRANSIENT, message)
}

internal fun requireStarted(status: Int, message: String) {
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

internal fun callbackFailure(message: String, status: Int): GattException {
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

internal fun CompletableDeferred<Unit>.completeWrite(status: Int, message: String) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        complete(Unit)
    } else {
        completeExceptionally(callbackFailure(message, status))
    }
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> androidGattCall(message: String, action: () -> T): T = try {
    action()
} catch (error: RuntimeException) {
    val kind = when (error) {
        is SecurityException -> GattFailureKind.PERMISSION
        is IllegalArgumentException -> GattFailureKind.INVALID_VALUE
        is UnsupportedOperationException -> GattFailureKind.UNSUPPORTED
        else -> GattFailureKind.UNKNOWN
    }
    throw GattException(kind, message, error)
}

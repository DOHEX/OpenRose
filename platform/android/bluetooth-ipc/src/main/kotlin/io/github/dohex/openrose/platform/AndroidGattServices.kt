package io.github.dohex.openrose.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import io.github.dohex.openrose.core.transport.GattCharacteristic
import io.github.dohex.openrose.core.transport.GattException
import io.github.dohex.openrose.core.transport.GattFailureKind
import io.github.dohex.openrose.core.transport.GattService
import java.util.IdentityHashMap

/** Matches shared characteristics to the exact native instances in the latest discovered service table. */
internal class AndroidGattServices {
    private var characteristics = IdentityHashMap<GattCharacteristic, BluetoothGattCharacteristic>()
    private var sharedCharacteristics = IdentityHashMap<BluetoothGattCharacteristic, GattCharacteristic>()

    @SuppressLint("MissingPermission")
    fun readServices(gatt: BluetoothGatt): List<GattService> {
        val byShared = IdentityHashMap<GattCharacteristic, BluetoothGattCharacteristic>()
        val byNative = IdentityHashMap<BluetoothGattCharacteristic, GattCharacteristic>()
        val services = gatt.services.map { service ->
            GattService(
                service.uuid.toString(),
                service.characteristics.map { native ->
                    byNative.getOrPut(native) {
                        val properties = native.properties
                        val shared = GattCharacteristic(
                            native.uuid.toString(),
                            canWrite = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                            canWriteWithoutResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                            canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                            canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                        )
                        byShared[shared] = native
                        shared
                    }
                },
            )
        }
        characteristics = byShared
        sharedCharacteristics = byNative
        return services
    }

    fun requireCharacteristic(characteristic: GattCharacteristic): BluetoothGattCharacteristic = characteristics[characteristic]
        ?: throw GattException(GattFailureKind.UNSUPPORTED, "蓝牙特征不可用")

    fun shared(native: BluetoothGattCharacteristic): GattCharacteristic? = sharedCharacteristics[native]

    fun clear() {
        characteristics.clear()
        sharedCharacteristics.clear()
    }
}

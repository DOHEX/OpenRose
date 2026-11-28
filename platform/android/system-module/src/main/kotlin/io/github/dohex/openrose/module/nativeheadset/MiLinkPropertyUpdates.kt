package io.github.dohex.openrose.module.nativeheadset

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.androidintegration.wire.NativeSessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

internal class MiLinkPropertyUpdates(
    private val directory: NativeHeadsetDirectory,
    private val hooks: NativeHeadsetHooks,
    private val module: XposedModule,
) {
    private val callbackHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastPublished: NativeHeadsetSnapshot? = null

    private data class Controller(
        val listener: WeakReference<Any>,
        val device: BluetoothDevice,
        val reference: NativeSessionReference,
        val address: String,
    )

    private val remembered = AtomicReference<Controller?>()

    fun publish(fresh: NativeHeadsetSnapshot?) {
        val previous = lastPublished
        lastPublished = fresh
        val controller = remembered.get() ?: return
        if (fresh == null || controller.reference != fresh.reference || controller.address !in fresh.addresses) {
            remembered.compareAndSet(controller, null)
            return
        }
        if (previous != null && !previous.sameReportedState(fresh) && fresh.matchesAddress(bluetoothAddress(controller.device))) {
            publishChangedProperties(controller, previous, fresh)
        }
    }

    private fun publishChangedProperties(controller: Controller, previous: NativeHeadsetSnapshot, fresh: NativeHeadsetSnapshot) {
        val noiseChanged = previous.fields[DeviceField.NOISE_MODE] != fresh.fields[DeviceField.NOISE_MODE]
        val batteryChanged = BATTERY_STATE_FIELDS.any { field -> previous.fields[field] != fresh.fields[field] }
        val canUpdateBattery = SystemHeadsetMapping.miLinkSummaryBattery(fresh.fields) != null
        if (noiseChanged) {
            if (SystemHeadsetMapping.miLinkAncState(fresh.fields) != null) {
                notifyHeadsetPropertyChanged(controller, UPDATE_ANC_STATE)
                if (canUpdateBattery) notifyHeadsetPropertyChanged(controller, UPDATE_HEADSET_PROPERTY)
            } else {
                hooks.report("UNAVAILABLE", "noise state changed to a value MiLink cannot represent")
            }
        }
        if (batteryChanged && !canUpdateBattery) {
            hooks.report("UNAVAILABLE", "battery state changed to values MiLink summary cannot represent")
        } else if (batteryChanged && !noiseChanged) {
            notifyHeadsetPropertyChanged(controller, UPDATE_HEADSET_PROPERTY)
        }
    }

    fun remember(chain: XposedInterface.Chain, snapshot: NativeHeadsetSnapshot) {
        val controller = chain.thisObject ?: return
        val device = chain.args.firstOrNull() as? BluetoothDevice ?: return
        val address = normalizeBluetoothAddress(runCatching { device.address }.getOrNull()) ?: return
        if (!snapshot.matchesAddress(address)) return
        val binding = Controller(WeakReference(controller), device, snapshot.reference, address)
        remembered.updateAndGet { previous ->
            if (previous == null) return@updateAndGet binding
            val sameListener = previous.listener.get() === controller && previous.device === device
            if (sameListener && previous.reference == binding.reference && previous.address == binding.address) previous else binding
        }
    }

    private fun notifyHeadsetPropertyChanged(binding: Controller, updateType: Int) {
        val controller = binding.listener.get() ?: return
        val device = binding.device
        fun isCurrent(): Boolean = remembered.get() === binding && directory.session(binding.address)?.reference == binding.reference
        if (!isCurrent()) return
        callbackHandler.post {
            if (!isCurrent()) return@post
            if (normalizeBluetoothAddress(bluetoothAddress(device)) != binding.address) return@post
            invokePropertyChangeListener(controller, device, updateType)
        }
    }

    private fun invokePropertyChangeListener(controller: Any, device: BluetoothDevice, updateType: Int) {
        hostAdapterCall(onFailure = { failure ->
            module.log(Log.WARN, TAG, "headset_property_callback_failed updateType=$updateType", failure)
            hooks.report("UNAVAILABLE", "headset_property_callback_failed:$updateType")
        }) {
            val listener = findObjectField(controller, "headsetPropertyChangeListener") ?: return
            val invoke = findMethodByNameAndCount(listener.javaClass, "invoke", 2)
                ?: throw NoSuchMethodException("headsetPropertyChangeListener.invoke/2")
            invoke.invoke(listener, device, updateType)
        }
    }

    private fun findObjectField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun findMethodByNameAndCount(type: Class<*>, name: String, count: Int): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val method = current.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == count }
            if (method != null) return runCatching { method.apply { isAccessible = true } }.getOrNull()
            current = current.superclass
        }
        return null
    }

    private companion object {
        const val TAG = "OpenRose-MiLink"
        const val UPDATE_ANC_STATE = 8
        const val UPDATE_HEADSET_PROPERTY = 4
        val BATTERY_STATE_FIELDS = setOf(
            DeviceField.LEFT_BATTERY,
            DeviceField.RIGHT_BATTERY,
            DeviceField.CASE_BATTERY,
            DeviceField.LEFT_CHARGING,
            DeviceField.RIGHT_CHARGING,
        )
    }
}

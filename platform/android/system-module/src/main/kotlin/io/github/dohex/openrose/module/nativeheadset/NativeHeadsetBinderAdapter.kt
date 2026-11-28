package io.github.dohex.openrose.module.nativeheadset

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

class NativeHeadsetBinderAdapter(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
    private val callbacks: AuthenticatedNativeHeadsetCallbacks,
) {
    private val directory = NativeHeadsetDirectory(SystemClock::elapsedRealtime)
    private val bindings = NativeBinderBindings(directory)
    private val hooks = NativeHeadsetHooks(module, classLoader, callbacks, FEATURE, TAG, "binder")
    private val listeners = NativeBinderCallbacks(directory, hooks, module)
    private val methods = BinderMethods()
    private val transactions = Transactions()

    val hookHandles: List<XposedInterface.HookHandle> get() = hooks.handles

    @Synchronized
    fun install(): List<XposedInterface.HookHandle> {
        methods.installServiceEntry()
        methods.installBinderClass("com.android.bluetooth.ble.app.headset.BinderC6776v")
        methods.installBinderClass("com.android.bluetooth.ble.app.headset.v")
        transactions.installTransactFallback()
        hooks.report(if (hookHandles.isEmpty()) "UNAVAILABLE" else "RESOLVED", "native headset binder probes installed=${hookHandles.size}")
        return hookHandles
    }

    fun publish(snapshot: NativeHeadsetSnapshot?) = listeners.publish(directory.publishSession(snapshot))

    fun publishBonded(addresses: Set<String>) = directory.publishBonded(addresses)

    private fun handleNoiseAction(owner: Any?, device: Any?, operation: String, action: (NativeHeadsetSnapshot) -> Unit): Boolean {
        val snapshot = bindings.snapshotForActionDevice(owner, device)
        if (snapshot == null) {
            if (!bindings.shouldSuppressManagedAction(owner, device)) return false
            hooks.report("UNAVAILABLE", "$operation blocked because the managed address has no matching current session")
        } else {
            action(snapshot)
        }
        return true
    }

    private fun changeNoiseMode(
        owner: Any?,
        device: Any?,
        nativeMode: Int?,
        operation: String,
    ): Boolean = handleNoiseAction(owner, device, operation) { snapshot ->
        val mode = nativeMode?.let(SystemHeadsetMapping::noiseModeFromSystem)
        if (mode == null) {
            hooks.report("UNSUPPORTED", "$operation received an unmapped native mode")
        } else {
            submit(snapshot, mode, bluetoothAddress(device))
        }
    }

    private fun registerBoundCallback(owner: Any?, readCallback: () -> Any?): Boolean {
        val bound = bindings.boundHeadset(owner) ?: return false
        val snapshot = bound.snapshot ?: return blockStaleRegistration(owner, bound.address)
        return readCallback()?.let { listeners.registerCallback(it, snapshot, bound.address) } == true
    }

    private fun registerDeviceCallback(owner: Any?, device: BluetoothDevice?, callback: Any?): Boolean {
        val address = bluetoothAddress(device) ?: return false
        val snapshot = bindings.snapshotForActionDevice(owner, device) ?: return blockStaleRegistration(owner, device)
        return callback != null && listeners.registerCallback(callback, snapshot, address)
    }

    private fun blockStaleRegistration(owner: Any?, target: Any?): Boolean {
        if (!bindings.shouldSuppressManagedAction(owner, target)) return false
        hooks.report("UNAVAILABLE", "callback registration blocked for a stale managed session")
        return true
    }

    private fun submit(snapshot: NativeHeadsetSnapshot, mode: String, address: String?) {
        hooks.submitNoise(snapshot, mode, address) { directory.current() }
    }

    private fun reportIdentified(query: String) = hooks.report("IDENTIFIED", "$query identified ROSE earphones")

    private inner class BinderMethods {
        private val installedBinderClasses = ConcurrentHashMap.newKeySet<String>()

        fun installServiceEntry() {
            val service = hooks.loadClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService") ?: return
            val onBind = resolveMethod(service, "onBind", arrayOf(Intent::class.java)) ?: run {
                hooks.reportResolution(service.name, "onBind(Intent)", "METHOD_MISSING")
                return
            }
            if (!IBinder::class.java.isAssignableFrom(onBind.returnType)) {
                hooks.reportResolution(service.name, "onBind(Intent)", "RETURN_TYPE_MISMATCH")
                return
            }
            hooks.installAfter(onBind) { result ->
                (result as? IBinder)?.let { installBinderClass(it.javaClass) }
            }
        }

        fun installBinderClass(className: String) {
            val binderClass = hostAdapterCall(onFailure = { failure ->
                module.log(Log.INFO, TAG, "optional_binder_class_missing class=$className", failure)
                hooks.report("UNAVAILABLE", "optional_binder_class_missing:$className")
                return
            }) {
                classLoader.loadClass(className)
            }
            installBinderClass(binderClass)
        }

        fun installBinderClass(binderClass: Class<*>) {
            if (!installedBinderClasses.add(binderClass.name)) return
            val device = BluetoothDevice::class.java
            val string = String::class.java

            hooks.hook(binderClass, "checkSupport", arrayOf(device), string) { chain ->
                val address = bluetoothAddress(chain.args.getOrNull(0))
                bindings.rememberAddress(chain.thisObject, address)
                identityAnswer(chain.executable.name, address, NativeHeadsetDirectory.BINDER_SUPPORT_MASK)
            }
            hooks.hook(binderClass, "getDeviceInfo", arrayOf(string), string) { chain ->
                val address = chain.args.getOrNull(0) as? String
                bindings.rememberAddress(chain.thisObject, address)
                identityAnswer(chain.executable.name, address, NativeHeadsetDirectory.BINDER_SUPPORT_MASK)
            }

            for (name in listOf("isMiTWS", "mo19771O0", "O0", "checkIsMiTWS", "mo19766B", "B")) {
                hooks.hook(binderClass, name, arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
                    val address = chain.args.getOrNull(0) as? String
                    bindings.rememberAddress(chain.thisObject, address)
                    identityAnswer(chain.executable.name, address, true)
                }
            }
            for (name in listOf("isSupportAudioSwitch", "mo19775z1", "z1")) {
                hooks.hook(binderClass, name, arrayOf(string), string) { chain ->
                    identityAnswer(chain.executable.name, chain.args.getOrNull(0) as? String, NativeHeadsetDirectory.BINDER_AUDIO_SWITCH)
                }
            }
            hooks.hook(binderClass, "getRingFindState", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
                directory.session(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
                HookDecision.Return(false)
            }
            installNoiseMethods(binderClass)
            installCallbackMethods(binderClass)
        }

        private fun installNoiseMethods(binderClass: Class<*>) {
            val device = BluetoothDevice::class.java
            hooks.hook(binderClass, "changeAncMode", arrayOf(Int::class.javaPrimitiveType!!, device), Void.TYPE) { chain ->
                val targetDevice = chain.args.getOrNull(1)
                val handled = changeNoiseMode(chain.thisObject, targetDevice, chain.args.firstOrNull() as? Int, "changeAncMode")
                if (handled) HookDecision.Return(null) else HookDecision.Proceed
            }
            hooks.hook(binderClass, "changeAncLevel", arrayOf(String::class.java, device), Void.TYPE) { chain ->
                val handled = handleNoiseAction(chain.thisObject, chain.args.getOrNull(1), "changeAncLevel") { snapshot ->
                    hooks.report("UNSUPPORTED", "changeAncLevel has no proven ROSE level mapping for ${snapshot.profile}")
                }
                if (handled) HookDecision.Return(null) else HookDecision.Proceed
            }
        }

        private fun installCallbackMethods(binderClass: Class<*>) {
            val callbackClass = callbackClassOrNull() ?: run {
                hooks.report("UNAVAILABLE", "IMiuiHeadsetCallback class missing; using onTransact fallback if available")
                return
            }
            hooks.hook(binderClass, "register", arrayOf(callbackClass), Void.TYPE) { chain ->
                val handled = registerBoundCallback(chain.thisObject) { chain.args.firstOrNull() }
                if (handled) HookDecision.Return(null) else HookDecision.Proceed
            }
            val device = BluetoothDevice::class.java
            hooks.hook(binderClass, "registerCallbackDevice", arrayOf(callbackClass, device), Void.TYPE) { chain ->
                val targetDevice = chain.args.getOrNull(1) as? BluetoothDevice
                val handled = registerDeviceCallback(chain.thisObject, targetDevice, chain.args.firstOrNull())
                if (handled) HookDecision.Return(null) else HookDecision.Proceed
            }
            hooks.hook(binderClass, "unregister", arrayOf(callbackClass, device), Void.TYPE) { chain ->
                val snapshot = directory.session(bluetoothAddress(chain.args.getOrNull(1))) ?: return@hook HookDecision.Proceed
                val callback = chain.args.firstOrNull() ?: return@hook HookDecision.Proceed
                listeners.removeCallback(callback)
                hooks.report("RESOLVED", "callback removed for ${snapshot.deviceId}")
                HookDecision.Return(null)
            }
        }

        private fun identityAnswer(query: String, address: String?, value: Any): HookDecision {
            if (!directory.identifies(address)) return HookDecision.Proceed
            reportIdentified(query)
            return HookDecision.Return(value)
        }

        @SuppressLint("PrivateApi")
        private fun callbackClassOrNull(): Class<*>? = hostAdapterCall(onFailure = { failure ->
            module.log(Log.WARN, TAG, "callback_class_missing class=$CALLBACK_CLASS", failure)
            null
        }) {
            classLoader.loadClass(CALLBACK_CLASS)
        }
    }

    private inner class Transactions {
        fun installTransactFallback() {
            val stub = hooks.loadClass("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub") ?: return
            val transact = resolveMethod(
                stub,
                "onTransact",
                arrayOf(Int::class.javaPrimitiveType!!, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType!!),
            ) ?: run {
                hooks.reportResolution(stub.name, "onTransact(int,Parcel,Parcel,int)", "METHOD_MISSING")
                return
            }
            if (transact.returnType != Boolean::class.javaPrimitiveType) {
                hooks.reportResolution(stub.name, "onTransact", "RETURN_TYPE_MISMATCH")
                return
            }
            hooks.installDecision(transact) { chain -> interceptTransact(chain) }
        }

        private fun interceptTransact(chain: XposedInterface.Chain): HookDecision {
            val code = chain.args.getOrNull(0) as? Int ?: return HookDecision.Proceed
            val data = chain.args.getOrNull(1) as? Parcel ?: return HookDecision.Proceed
            val reply = chain.args.getOrNull(2) as? Parcel ?: return HookDecision.Proceed
            val originalPosition = data.dataPosition()
            val handled = try {
                hostAdapterCall(onFailure = { failure ->
                    module.log(Log.WARN, TAG, "onTransact_probe_failed code=$code", failure)
                    hooks.report("UNAVAILABLE", "onTransact_probe_failed:$code")
                    false
                }) {
                    data.enforceInterface(SERVICE_DESCRIPTOR)
                    dispatchTransaction(chain.thisObject, code, data, reply)
                }
            } finally {
                data.setDataPosition(originalPosition)
            }
            return if (handled) HookDecision.Return(true) else HookDecision.Proceed
        }

        private fun dispatchTransaction(owner: Any?, code: Int, data: Parcel, reply: Parcel): Boolean {
            return when (code) {
                TRANSACTION_CHECK_SUPPORT -> {
                    val address = data.readDevice()?.address
                    bindings.rememberAddress(owner, address)
                    replyIdentity("checkSupport", address, reply) { writeString(NativeHeadsetDirectory.BINDER_SUPPORT_MASK) }
                }

                TRANSACTION_GET_DEVICE_INFO -> {
                    val address = data.readString()
                    bindings.rememberAddress(owner, address)
                    replyIdentity("getDeviceInfo", address, reply) { writeString(NativeHeadsetDirectory.BINDER_SUPPORT_MASK) }
                }

                TRANSACTION_IS_MITWS, TRANSACTION_CHECK_IS_MITWS -> {
                    val address = data.readString()
                    bindings.rememberAddress(owner, address)
                    replyIdentity("transaction=$code", address, reply) { writeInt(1) }
                }

                TRANSACTION_IS_SUPPORT_AUDIO_SWITCH -> {
                    replyIdentity("isSupportAudioSwitch", data.readString(), reply) {
                        writeString(NativeHeadsetDirectory.BINDER_AUDIO_SWITCH)
                    }
                }

                TRANSACTION_GET_RING_FIND_STATE -> {
                    val address = data.readString()
                    directory.session(address) ?: return false
                    reply.writeNoException()
                    reply.writeInt(0)
                    true
                }

                TRANSACTION_CHANGE_ANC_MODE, TRANSACTION_CHANGE_ANC_LEVEL ->
                    reply.acknowledgeIfHandled(handleNoiseTransaction(owner, code, data))

                TRANSACTION_REGISTER -> reply.acknowledgeIfHandled(registerBoundCallback(owner) { data.readCallback() })

                TRANSACTION_REGISTER_CALLBACK_DEVICE -> {
                    val callback = data.readCallback()
                    reply.acknowledgeIfHandled(registerDeviceCallback(owner, data.readDevice(), callback))
                }

                TRANSACTION_UNREGISTER -> {
                    val binder = data.readStrongBinder()
                    if (binder != null) listeners.removeCallback(binder)
                    false
                }

                else -> false
            }
        }

        private fun Parcel.acknowledgeIfHandled(handled: Boolean): Boolean {
            if (handled) writeNoException()
            return handled
        }

        private fun handleNoiseTransaction(owner: Any?, code: Int, data: Parcel): Boolean {
            if (code == TRANSACTION_CHANGE_ANC_MODE) {
                val mode = data.readInt()
                return changeNoiseMode(owner, data.readDevice(), mode, "changeAncMode transaction")
            }
            val level = data.readString()
            return handleNoiseAction(owner, data.readDevice(), "changeAncLevel transaction") {
                hooks.report("UNSUPPORTED", "changeAncLevel transaction has no proven ROSE mapping (level=${level.orEmpty().take(24)})")
            }
        }

        private fun replyIdentity(query: String, address: String?, reply: Parcel, write: Parcel.() -> Unit): Boolean {
            if (!directory.identifies(address)) return false
            reply.writeNoException()
            reply.write()
            reportIdentified(query)
            return true
        }

        private fun Parcel.readDevice(): BluetoothDevice? = hostAdapterCall(onFailure = { failure ->
            module.log(Log.WARN, TAG, "parcel_bluetooth_device_read_failed", failure)
            null
        }) {
            if (readInt() != 0) BluetoothDevice.CREATOR.createFromParcel(this) else null
        }

        @SuppressLint("PrivateApi")
        private fun Parcel.readCallback(): Any? {
            val binder = runCatching { readStrongBinder() }.getOrNull() ?: return null
            return hostAdapterCall(onFailure = { failure ->
                module.log(Log.WARN, TAG, "callback_asInterface_failed", failure)
                null
            }) {
                val stub = classLoader.loadClass("$CALLBACK_CLASS\$Stub")
                val method = resolveMethod(stub, "asInterface", arrayOf(IBinder::class.java)) ?: return null
                method.invoke(null, binder)
            }
        }
    }

    private companion object {
        const val TAG = "OpenRose-HeadsetBinder"
        const val FEATURE = "SYSTEM_HEADSET"
        const val SERVICE_DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"
        const val CALLBACK_CLASS = "com.android.bluetooth.ble.app.IMiuiHeadsetCallback"
        const val TRANSACTION_CHECK_SUPPORT = 1
        const val TRANSACTION_REGISTER = 2
        const val TRANSACTION_UNREGISTER = 3
        const val TRANSACTION_CHANGE_ANC_MODE = 9
        const val TRANSACTION_CHANGE_ANC_LEVEL = 10
        const val TRANSACTION_GET_DEVICE_INFO = 11
        const val TRANSACTION_IS_MITWS = 18
        const val TRANSACTION_CHECK_IS_MITWS = 19
        const val TRANSACTION_IS_SUPPORT_AUDIO_SWITCH = 20
        const val TRANSACTION_GET_RING_FIND_STATE = 24
        const val TRANSACTION_REGISTER_CALLBACK_DEVICE = 16
    }
}

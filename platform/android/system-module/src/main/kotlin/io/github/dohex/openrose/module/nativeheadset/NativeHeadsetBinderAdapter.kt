package io.github.dohex.openrose.module.nativeheadset

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.SystemRequestGate
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.androidintegration.wire.NativeSessionReference
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hooks the native `IMiuiHeadsetService` binder used by HyperOS' Bluetooth headset page.
 *
 * Identity queries (`isMiTWS`, `checkIsMiTWS`, `isSupportAudioSwitch`, `checkSupport`, `getDeviceInfo`) answer with
 * the pinned OppoPods reference's values for the current session and for the module host's bonded ROSE earphones, so
 * a source device the earphones are not connected to yet still offers headset circulation (ADR 0017). Status and
 * actions come only from the latest authenticated session. The adapter does not create a Bluetooth client, send Xiaomi
 * broadcasts, or synthesize wear or spatial state.
 */
class NativeHeadsetBinderAdapter(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
    private val callbacks: AuthenticatedNativeHeadsetCallbacks,
) {
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val installedIds = ConcurrentHashMap.newKeySet<String>()
    private val installedBinderClasses = ConcurrentHashMap.newKeySet<String>()
    private val binderAddress = Collections.synchronizedMap(WeakHashMap<Any, BinderAddress>())
    private val directory = NativeHeadsetDirectory(SystemClock::elapsedRealtime)
    private val registeredCallbacks = ConcurrentHashMap<IBinder, RegisteredCallback>()
    private val callbackHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastReportedSnapshot: NativeHeadsetSnapshot? = null

    @Volatile private var closed = false

    val hookHandles: List<XposedInterface.HookHandle>
        get() = handles.toList()

    /** Install the service entry hook plus exact binder APIs available in this ROM process. */
    @Synchronized
    fun install(): List<XposedInterface.HookHandle> {
        if (closed) return hookHandles
        installServiceEntry()
        installBinderClass("com.android.bluetooth.ble.app.headset.BinderC6776v")
        installBinderClass("com.android.bluetooth.ble.app.headset.v")
        installTransactFallback()
        report(FEATURE, if (handles.isEmpty()) "UNAVAILABLE" else "RESOLVED", "native headset binder probes installed=${handles.size}")
        return hookHandles
    }

    /** Called by the authenticated bridge reader; status callbacks are sent only from this data. */
    fun publish(snapshot: NativeHeadsetSnapshot?) {
        val fresh = directory.publishSession(snapshot)
        registeredCallbacks.values.toList().forEach { target ->
            if (fresh == null || !target.matches(fresh)) {
                registeredCallbacks.remove(target.binder, target)
                unlinkDeath(target)
            }
        }
        if (fresh == null) {
            lastReportedSnapshot = null
            return
        }
        if (lastReportedSnapshot?.sameReportedState(fresh) != true) {
            lastReportedSnapshot = fresh
            refreshCallbacks(fresh)
        }
    }

    private fun refreshCallbacks(fresh: NativeHeadsetSnapshot) {
        val targets = registeredCallbacks.values.filter { it.matches(fresh) }
        if (targets.isEmpty()) return
        val status = SystemHeadsetMapping.binderStatus(fresh.fields) as? SystemHeadsetMapping.BinderStatus.Payload
        if (status == null) {
            report(FEATURE, "UNAVAILABLE", "status payload contains fields the current core snapshot cannot represent")
            return
        }
        if (status.caseOmitted) {
            report(FEATURE, "UNAVAILABLE", "case battery omitted because its charging state is not in the core snapshot")
        }
        targets.forEach { target -> sendRefresh(target, fresh, status.text) }
    }

    /** The module host's bonded ROSE earphones; identity queries answer for them even with no session (ADR 0017). */
    fun publishBonded(addresses: Set<String>) {
        if (!closed) directory.publishBonded(addresses)
    }

    /** Release API 102 hook handles, callback registrations, and the local snapshot on shutdown. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        handles.forEach { handle -> runCatching { handle.unhook() } }
        handles.clear()
        installedIds.clear()
        installedBinderClasses.clear()
        registeredCallbacks.values.forEach(::unlinkDeath)
        registeredCallbacks.clear()
        synchronized(binderAddress) { binderAddress.clear() }
        directory.clear()
        lastReportedSnapshot = null
        callbackHandler.removeCallbacksAndMessages(null)
    }

    private fun installServiceEntry() {
        val service = loadClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService") ?: return
        val onBind = resolveMethod(service, "onBind", arrayOf(Intent::class.java)) ?: run {
            reportResolution(service.name, "onBind(Intent)", "METHOD_MISSING")
            return
        }
        if (!IBinder::class.java.isAssignableFrom(onBind.returnType)) {
            reportResolution(service.name, "onBind(Intent)", "RETURN_TYPE_MISMATCH")
            return
        }
        installAfter(onBind) { result ->
            (result as? IBinder)?.let { installBinderClass(it.javaClass) }
        }
    }

    private fun installBinderClass(className: String) {
        val binderClass = try {
            classLoader.loadClass(className)
        } catch (failure: Throwable) {
            module.log(Log.INFO, TAG, "optional_binder_class_missing class=$className", failure)
            report(FEATURE, "UNAVAILABLE", "optional_binder_class_missing:$className")
            return
        }
        installBinderClass(binderClass)
    }

    private fun installBinderClass(binderClass: Class<*>) {
        if (!installedBinderClasses.add(binderClass.name)) return
        val device = BluetoothDevice::class.java
        val string = String::class.java

        hook(binderClass, "checkSupport", arrayOf(device), string) { chain ->
            val address = bluetoothAddress(chain.args.getOrNull(0))
            rememberAddress(chain.thisObject, address)
            identityAnswer(chain.executable.name, address, NativeHeadsetDirectory.BINDER_SUPPORT_MASK)
        }
        hook(binderClass, "getDeviceInfo", arrayOf(string), string) { chain ->
            val address = chain.args.getOrNull(0) as? String
            rememberAddress(chain.thisObject, address)
            identityAnswer(chain.executable.name, address, NativeHeadsetDirectory.BINDER_SUPPORT_MASK)
        }

        for (name in listOf("isMiTWS", "mo19771O0", "O0", "checkIsMiTWS", "mo19766B", "B")) {
            hook(binderClass, name, arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
                val address = chain.args.getOrNull(0) as? String
                rememberAddress(chain.thisObject, address)
                identityAnswer(chain.executable.name, address, true)
            }
        }
        for (name in listOf("isSupportAudioSwitch", "mo19775z1", "z1")) {
            hook(binderClass, name, arrayOf(string), string) { chain ->
                identityAnswer(chain.executable.name, chain.args.getOrNull(0) as? String, NativeHeadsetDirectory.BINDER_AUDIO_SWITCH)
            }
        }
        hook(binderClass, "getRingFindState", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
            snapshotForAddress(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
            HookDecision.Return(false)
        }
        installNoiseMethods(binderClass)
        installCallbackMethods(binderClass)
    }

    private fun installNoiseMethods(binderClass: Class<*>) {
        val device = BluetoothDevice::class.java
        hook(binderClass, "changeAncMode", arrayOf(Int::class.javaPrimitiveType!!, device), Void.TYPE) { chain ->
            val targetDevice = chain.args.getOrNull(1)
            val handled = changeNoiseMode(chain.thisObject, targetDevice, chain.args.firstOrNull() as? Int, "changeAncMode")
            if (handled) HookDecision.Return(null) else HookDecision.Proceed
        }
        hook(binderClass, "changeAncLevel", arrayOf(String::class.java, device), Void.TYPE) { chain ->
            val handled = handleNoiseAction(chain.thisObject, chain.args.getOrNull(1), "changeAncLevel") { snapshot ->
                report(FEATURE, "UNSUPPORTED", "changeAncLevel has no proven ROSE level mapping for ${snapshot.profile}")
            }
            if (handled) HookDecision.Return(null) else HookDecision.Proceed
        }
    }

    private fun installCallbackMethods(binderClass: Class<*>) {
        val callbackClass = callbackClassOrNull() ?: run {
            report(FEATURE, "UNAVAILABLE", "IMiuiHeadsetCallback class missing; using onTransact fallback if available")
            return
        }
        hook(binderClass, "register", arrayOf(callbackClass), Void.TYPE) { chain ->
            val handled = registerBoundCallback(chain.thisObject) { chain.args.firstOrNull() }
            if (handled) HookDecision.Return(null) else HookDecision.Proceed
        }
        val device = BluetoothDevice::class.java
        hook(binderClass, "registerCallbackDevice", arrayOf(callbackClass, device), Void.TYPE) { chain ->
            val handled = registerDeviceCallback(chain.thisObject, chain.args.getOrNull(1) as? BluetoothDevice, chain.args.firstOrNull())
            if (handled) HookDecision.Return(null) else HookDecision.Proceed
        }
        hook(binderClass, "unregister", arrayOf(callbackClass, device), Void.TYPE) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(1)) ?: return@hook HookDecision.Proceed
            val callback = chain.args.firstOrNull() ?: return@hook HookDecision.Proceed
            removeCallback(callback)
            report(FEATURE, "RESOLVED", "callback removed for ${snapshot.deviceId}")
            HookDecision.Return(null)
        }
    }

    private fun installTransactFallback() {
        val stub = loadClass("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub") ?: return
        val transact = resolveMethod(
            stub,
            "onTransact",
            arrayOf(Int::class.javaPrimitiveType!!, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType!!),
        ) ?: run {
            reportResolution(stub.name, "onTransact(int,Parcel,Parcel,int)", "METHOD_MISSING")
            return
        }
        if (transact.returnType != Boolean::class.javaPrimitiveType) {
            reportResolution(stub.name, "onTransact", "RETURN_TYPE_MISMATCH")
            return
        }
        installDecision(transact) { chain -> interceptTransact(chain) }
    }

    private fun interceptTransact(chain: XposedInterface.Chain): HookDecision {
        val code = chain.args.getOrNull(0) as? Int ?: return HookDecision.Proceed
        val data = chain.args.getOrNull(1) as? Parcel ?: return HookDecision.Proceed
        val reply = chain.args.getOrNull(2) as? Parcel ?: return HookDecision.Proceed
        val originalPosition = data.dataPosition()
        val handled = try {
            data.enforceInterface(SERVICE_DESCRIPTOR)
            dispatchTransaction(chain.thisObject, code, data, reply)
        } catch (failure: Throwable) {
            module.log(Log.WARN, TAG, "onTransact_probe_failed code=$code", failure)
            report(FEATURE, "UNAVAILABLE", "onTransact_probe_failed:$code")
            false
        } finally {
            data.setDataPosition(originalPosition)
        }
        return if (handled) HookDecision.Return(true) else HookDecision.Proceed
    }

    private fun dispatchTransaction(owner: Any?, code: Int, data: Parcel, reply: Parcel): Boolean {
        return when (code) {
            TRANSACTION_CHECK_SUPPORT -> {
                val address = data.readDevice()?.address
                rememberAddress(owner, address)
                replyIdentity("checkSupport", address, reply) { writeString(NativeHeadsetDirectory.BINDER_SUPPORT_MASK) }
            }

            TRANSACTION_GET_DEVICE_INFO -> {
                val address = data.readString()
                rememberAddress(owner, address)
                replyIdentity("getDeviceInfo", address, reply) { writeString(NativeHeadsetDirectory.BINDER_SUPPORT_MASK) }
            }

            TRANSACTION_IS_MITWS, TRANSACTION_CHECK_IS_MITWS -> {
                val address = data.readString()
                rememberAddress(owner, address)
                replyIdentity("transaction=$code", address, reply) { writeInt(1) }
            }

            TRANSACTION_IS_SUPPORT_AUDIO_SWITCH -> {
                replyIdentity("isSupportAudioSwitch", data.readString(), reply) {
                    writeString(NativeHeadsetDirectory.BINDER_AUDIO_SWITCH)
                }
            }

            TRANSACTION_GET_RING_FIND_STATE -> {
                val address = data.readString()
                snapshotForAddress(address) ?: return false
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
                if (binder != null) removeCallback(binder)
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
            report(FEATURE, "UNSUPPORTED", "changeAncLevel transaction has no proven ROSE mapping (level=${level.orEmpty().take(24)})")
        }
    }

    private fun handleNoiseAction(owner: Any?, device: Any?, operation: String, action: (NativeHeadsetSnapshot) -> Unit): Boolean {
        val snapshot = snapshotForActionDevice(owner, device)
        if (snapshot == null) {
            if (!shouldSuppressManagedAction(owner, device)) return false
            report(FEATURE, "UNAVAILABLE", "$operation blocked because the managed address has no matching current session")
        } else {
            action(snapshot)
        }
        // A managed action must not fall through to a second vendor control path for ROSE.
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
            report(FEATURE, "UNSUPPORTED", "$operation received an unmapped native mode")
        } else {
            submit(snapshot, mode, bluetoothAddress(device))
        }
    }

    private fun registerBoundCallback(owner: Any?, readCallback: () -> Any?): Boolean {
        val binding = synchronized(binderAddress) { binderAddress[owner] } ?: return false
        val snapshot = snapshotForBoundAddress(owner) ?: return blockStaleRegistration(owner, binding.address)
        return readCallback()?.let { registerCallback(it, snapshot, binding.address) } == true
    }

    private fun registerDeviceCallback(owner: Any?, device: BluetoothDevice?, callback: Any?): Boolean {
        val address = bluetoothAddress(device) ?: return false
        val snapshot = snapshotForActionDevice(owner, device) ?: return blockStaleRegistration(owner, device)
        return callback != null && registerCallback(callback, snapshot, address)
    }

    private fun blockStaleRegistration(owner: Any?, target: Any?): Boolean {
        if (!shouldSuppressManagedAction(owner, target)) return false
        report(FEATURE, "UNAVAILABLE", "callback registration blocked for a stale managed session")
        return true
    }

    private fun registerCallback(callback: Any, snapshot: NativeHeadsetSnapshot, address: String): Boolean {
        val binder = callbackBinder(callback) ?: run {
            report(FEATURE, "UNAVAILABLE", "callback binder unavailable")
            return false
        }
        val normalizedAddress = normalizeBluetoothAddress(address) ?: return false
        val death = IBinder.DeathRecipient { registeredCallbacks.remove(binder) }
        runCatching { binder.linkToDeath(death, 0) }.onFailure {
            report(FEATURE, "UNAVAILABLE", "callback binder already dead")
            return false
        }
        registeredCallbacks.put(
            binder,
            RegisteredCallback(callback, binder, normalizedAddress, snapshot.reference, death),
        )?.let(::unlinkDeath)
        return sendInitialStatus(binder, snapshot)
    }

    private fun sendInitialStatus(binder: IBinder, snapshot: NativeHeadsetSnapshot): Boolean {
        val status = SystemHeadsetMapping.binderStatus(snapshot.fields) as? SystemHeadsetMapping.BinderStatus.Payload
        if (status == null) {
            report(FEATURE, "UNAVAILABLE", "initial status callback cannot represent current snapshot")
        } else {
            val registered = registeredCallbacks[binder] ?: return false
            sendRefresh(registered, snapshot, status.text)
            report(FEATURE, "RESOLVED", "native refresh callback registered for ${snapshot.deviceId}")
        }
        return true
    }

    private fun sendRefresh(target: RegisteredCallback, snapshot: NativeHeadsetSnapshot, payload: String) {
        callbackHandler.post {
            if (closed || registeredCallbacks[target.binder] !== target) return@post
            if (directory.session(target.address)?.reference != target.reference) return@post
            try {
                val method = target.callback.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "refreshStatus" && candidate.parameterTypes.contentEquals(
                        arrayOf(String::class.java, String::class.java),
                    )
                } ?: throw NoSuchMethodException("refreshStatus(String,String)")
                method.isAccessible = true
                method.invoke(target.callback, target.address, payload)
            } catch (failure: Throwable) {
                registeredCallbacks.remove(target.binder, target)
                unlinkDeath(target)
                module.log(Log.WARN, TAG, "refreshStatus_callback_failed session=${snapshot.sessionId}", failure)
                report(FEATURE, "UNAVAILABLE", "refreshStatus callback failed for current session")
            }
        }
    }

    private fun snapshotForDevice(value: Any?): NativeHeadsetSnapshot? = (value as? BluetoothDevice)
        ?.let { device -> runCatching { snapshotForAddress(device.address) }.getOrNull() }

    private fun snapshotForAddress(address: String?): NativeHeadsetSnapshot? = directory.session(address)

    /** An identity query: answered for the current session and the bonded ROSE earphones, otherwise left to the system. */
    private fun identityAnswer(query: String, address: String?, value: Any): HookDecision {
        if (!directory.identifies(address)) return HookDecision.Proceed
        reportIdentified(query)
        return HookDecision.Return(value)
    }

    /** [identityAnswer] for the `onTransact` fallback; true when the reply was written. */
    private fun replyIdentity(query: String, address: String?, reply: Parcel, write: Parcel.() -> Unit): Boolean {
        if (!directory.identifies(address)) return false
        reply.writeNoException()
        reply.write()
        reportIdentified(query)
        return true
    }

    private fun reportIdentified(query: String) = report(FEATURE, "IDENTIFIED", "$query identified ROSE earphones")

    private fun snapshotForActionDevice(owner: Any?, value: Any?): NativeHeadsetSnapshot? {
        val device = value as? BluetoothDevice ?: return null
        val address = runCatching { device.address }.getOrNull() ?: return null
        val normalized = normalizeBluetoothAddress(address) ?: return null
        val snapshot = snapshotForAddress(normalized) ?: return null
        val binding = owner?.let { instance -> synchronized(binderAddress) { binderAddress[instance] } }
        if (binding != null && binding.address == normalized && !binding.admits(snapshot)) return null
        return snapshot
    }

    private fun bluetoothAddress(value: Any?): String? = (value as? BluetoothDevice)
        ?.let { device -> runCatching { device.address }.getOrNull() }

    private fun snapshotForBoundAddress(owner: Any?): NativeHeadsetSnapshot? {
        val binding = owner?.let { instance -> synchronized(binderAddress) { binderAddress[instance] } } ?: return null
        return snapshotForAddress(binding.address)?.takeIf(binding::admits)
    }

    private fun shouldSuppressManagedAction(owner: Any?, value: Any?): Boolean {
        val address = when (value) {
            is BluetoothDevice -> runCatching { value.address }.getOrNull()
            is String -> value
            else -> null
        }
        val normalized = normalizeBluetoothAddress(address) ?: return false
        if (!directory.isManaged(normalized)) return false
        val snapshot = snapshotForAddress(normalized)
        if (snapshot == null) return true
        val binding = owner?.let { instance -> synchronized(binderAddress) { binderAddress[instance] } }
        return binding != null && binding.address == normalized && !binding.admits(snapshot)
    }

    /**
     * Binds a binder instance to the ROSE address it identified. Without a session the binding carries no reference: its
     * address-less calls (`register`) stay off the vendor path until a session for that address exists, which it then
     * admits, as on the target source device of a headset circulation (ADR 0017).
     */
    private fun rememberAddress(owner: Any?, address: String?) {
        if (owner == null || !directory.identifies(address)) return
        val normalized = normalizeBluetoothAddress(address) ?: return
        val reference = snapshotForAddress(normalized)?.reference
        synchronized(binderAddress) { binderAddress[owner] = BinderAddress(normalized, reference) }
    }

    private fun submit(snapshot: NativeHeadsetSnapshot, mode: String, address: String?) {
        if (!snapshot.supportsNoise(mode)) {
            report(FEATURE, "UNSUPPORTED", "native mode=$mode is absent from current capability snapshot")
            return
        }
        val normalizedAddress = normalizeBluetoothAddress(address)
            ?.takeIf(snapshot.addresses::contains) ?: run {
            report(FEATURE, "UNAVAILABLE", "native mode=$mode request address is not in the authenticated snapshot")
            return
        }
        val result = NativeNoiseRequestGate.submit(snapshot, mode) {
            val current = directory.current()?.takeIf {
                !closed && it.reference == snapshot.reference &&
                    it.matchesAddress(normalizedAddress) && it.supportsNoise(mode)
            }
            if (current == null) false else callbacks.submitNoise(current, mode)
        }
        if (result == SystemRequestGate.Result.REJECTED) {
            module.log(Log.WARN, TAG, "native noise dispatch rejected mode=$mode")
        }
        report(
            FEATURE,
            if (result == SystemRequestGate.Result.REJECTED) "UNAVAILABLE" else "SUBMITTED",
            when (result) {
                SystemRequestGate.Result.ACCEPTED -> "mode=$mode session=${snapshot.sessionId}; awaiting device confirmation"
                SystemRequestGate.Result.DUPLICATE -> "mode=$mode session=${snapshot.sessionId}; duplicate vendor callback coalesced"
                SystemRequestGate.Result.REJECTED -> "mode=$mode session=${snapshot.sessionId}; authenticated control callback unavailable"
            },
        )
    }

    private fun callbackClassOrNull(): Class<*>? = try {
        classLoader.loadClass(CALLBACK_CLASS)
    } catch (failure: Throwable) {
        module.log(Log.WARN, TAG, "callback_class_missing class=$CALLBACK_CLASS", failure)
        null
    }

    private fun callbackBinder(callback: Any): IBinder? = runCatching {
        val method = resolveMethod(callback.javaClass, "asBinder", emptyArray()) ?: return null
        method.invoke(callback) as? IBinder
    }.onFailure { module.log(Log.WARN, TAG, "callback_asBinder_failed", it) }.getOrNull()

    private fun removeCallback(callback: Any) {
        val binder = if (callback is IBinder) callback else callbackBinder(callback) ?: return
        registeredCallbacks.remove(binder)?.let(::unlinkDeath)
    }

    private fun unlinkDeath(callback: RegisteredCallback) {
        runCatching { callback.binder.unlinkToDeath(callback.death, 0) }
    }

    private fun Parcel.readDevice(): BluetoothDevice? = try {
        if (readInt() != 0) BluetoothDevice.CREATOR.createFromParcel(this) else null
    } catch (failure: Throwable) {
        module.log(Log.WARN, TAG, "parcel_bluetooth_device_read_failed", failure)
        null
    }

    private fun Parcel.readCallback(): Any? {
        val binder = runCatching { readStrongBinder() }.getOrNull() ?: return null
        return try {
            val stub = classLoader.loadClass("$CALLBACK_CLASS\$Stub")
            val method = resolveMethod(stub, "asInterface", arrayOf(IBinder::class.java)) ?: return null
            method.invoke(null, binder)
        } catch (failure: Throwable) {
            module.log(Log.WARN, TAG, "callback_asInterface_failed", failure)
            null
        }
    }

    private fun loadClass(name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (failure: Throwable) {
        module.log(Log.WARN, TAG, "resolution_class_missing class=$name", failure)
        report(FEATURE, "UNAVAILABLE", "class_missing:$name")
        null
    }

    private fun hook(
        target: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        returnType: Class<*>,
        handler: (XposedInterface.Chain) -> HookDecision,
    ) {
        val method = resolveMethod(target, methodName, parameterTypes) ?: run {
            reportResolution(target.name, methodName, "METHOD_MISSING")
            return
        }
        if (method.returnType != returnType) {
            reportResolution(target.name, methodName, "RETURN_TYPE_MISMATCH")
            return
        }
        installDecision(method, handler)
    }

    private fun resolveMethod(target: Class<*>, name: String, parameters: Array<Class<*>>): Method? {
        var current: Class<*>? = target
        while (current != null) {
            val match = current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.contentEquals(parameters)
            }
            if (match != null) return runCatching { match.apply { isAccessible = true } }.getOrNull()
            current = current.superclass
        }
        return null
    }

    private fun reportResolution(className: String, methodName: String, status: String) {
        module.log(Log.WARN, TAG, "resolution_$status class=$className method=$methodName")
        report(FEATURE, "UNAVAILABLE", "${status.lowercase()}:$className.$methodName")
    }

    private fun report(feature: String, status: String, detail: String) {
        runCatching { callbacks.report(feature.take(64), componentStatus(status), detail.take(512)) }
            .onFailure { module.log(Log.WARN, TAG, "diagnostic_callback_failed feature=$feature status=$status", it) }
    }

    private fun componentStatus(status: String): String = when (status) {
        in BridgePresentationWire.COMPONENT_STATUSES -> status
        else -> BridgePresentationWire.ACTION_FAILED
    }

    private fun installAfter(method: Method, after: (Any?) -> Unit) {
        val id = hookId(method)
        if (!installedIds.add(id)) return
        try {
            val handle = module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        after(result)
                    } catch (failure: Throwable) {
                        module.log(Log.WARN, TAG, "hook_after_failed class=${method.declaringClass.name} method=${method.name}", failure)
                        report(FEATURE, "UNAVAILABLE", "hook_after_failed:${method.declaringClass.name}.${method.name}")
                    }
                    result
                }
            handles += handle
        } catch (failure: Throwable) {
            installedIds.remove(id)
            module.log(Log.ERROR, TAG, "hook_install_failed class=${method.declaringClass.name} method=${method.name}", failure)
            report(FEATURE, "UNAVAILABLE", "hook_failed:${method.declaringClass.name}.${method.name}")
        }
    }

    private fun installDecision(method: Method, handler: (XposedInterface.Chain) -> HookDecision) {
        val id = hookId(method)
        if (!installedIds.add(id)) return
        try {
            val handle = module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val outcome = try {
                        handler(chain)
                    } catch (failure: Throwable) {
                        module.log(Log.WARN, TAG, "hook_handler_failed class=${method.declaringClass.name} method=${method.name}", failure)
                        report(FEATURE, "UNAVAILABLE", "hook_handler_failed:${method.declaringClass.name}.${method.name}")
                        HookDecision.Proceed
                    }
                    if (outcome is HookDecision.Return) outcome.value else chain.proceed()
                }
            handles += handle
        } catch (failure: Throwable) {
            installedIds.remove(id)
            module.log(Log.ERROR, TAG, "hook_install_failed class=${method.declaringClass.name} method=${method.name}", failure)
            report(FEATURE, "UNAVAILABLE", "hook_failed:${method.declaringClass.name}.${method.name}")
        }
    }

    private fun hookId(method: Method): String = "openrose.native_headset.binder.${method.declaringClass.name}.${method.name}"
        .replace('-', '_')

    private data class RegisteredCallback(
        val callback: Any,
        val binder: IBinder,
        val address: String,
        val reference: NativeSessionReference,
        val death: IBinder.DeathRecipient,
    ) {
        fun matches(snapshot: NativeHeadsetSnapshot): Boolean = reference == snapshot.reference && address in snapshot.addresses
    }

    private data class BinderAddress(
        val address: String,
        /** Null when bound to bonded earphones with no session. */
        val reference: NativeSessionReference?,
    ) {
        /** A binding made in a session keeps to that session; one made with none admits the session for its address. */
        fun admits(snapshot: NativeHeadsetSnapshot): Boolean = reference == null || reference == snapshot.reference
    }

    private sealed interface HookDecision {
        data object Proceed : HookDecision
        data class Return(val value: Any?) : HookDecision
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

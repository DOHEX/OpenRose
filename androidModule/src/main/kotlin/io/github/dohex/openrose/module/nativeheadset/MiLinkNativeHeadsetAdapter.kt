package io.github.dohex.openrose.module.nativeheadset

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.SystemRequestGate
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.androidintegration.wire.NativeSessionReference
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Adapts the fixed MiLink runtime entry points used by HyperOS' Fusion device page.
 *
 * This class never opens Bluetooth, sends a broadcast, or keeps a second device state. The module
 * publishes snapshots from its existing authenticated bridge reader and supplies the callback
 * which queues a mode request into the same control runtime. Identity queries (`isMiTWS`, `checkIsMiTWS`,
 * `isSupportAudioSwitch`, the model id) also answer for the module host's bonded ROSE earphones with no session
 * (ADR 0017); battery, noise and every action come only from the current session.
 */
class MiLinkNativeHeadsetAdapter(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
    private val callbacks: AuthenticatedNativeHeadsetCallbacks,
    private val reportFeature: String = BridgePresentationWire.FUSION_CENTER,
    private val contextSupplier: (() -> Context?)? = null,
) {
    init {
        require(reportFeature == BridgePresentationWire.FUSION_CENTER || reportFeature == BridgePresentationWire.SYSTEM_HEADSET)
    }

    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val installedIds = mutableSetOf<String>()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val directory = NativeHeadsetDirectory(SystemClock::elapsedRealtime)

    /** The last published session, kept only to tell which reported state changed. */
    @Volatile private var lastPublished: NativeHeadsetSnapshot? = null

    @Volatile private var lastAncController: WeakReference<Any>? = null

    @Volatile private var lastAncDevice: BluetoothDevice? = null

    @Volatile private var lastAncReference: NativeSessionReference? = null

    @Volatile private var lastAncAddress: String? = null

    @Volatile private var closed = false

    val handles: List<XposedInterface.HookHandle>
        get() = hookHandles.toList()

    /** Install exact class and method signatures. Missing ROM APIs are reported individually. */
    @Synchronized
    fun install(): List<XposedInterface.HookHandle> {
        if (closed) return handles
        installMxBluetooth("com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager")
        installMxBluetooth("com.xiaomi.mxbluetoothsdk.service.MxBluetoothService")
        installProfileContext()
        installAncBatteryController()
        installHeadsetInfo()
        report(FEATURE, if (hookHandles.isEmpty()) "UNAVAILABLE" else "RESOLVED", "MiLink method probes installed=${hookHandles.size}")
        return handles
    }

    /** Called by the authenticated bridge reader after validating a complete frame. */
    fun publish(snapshot: NativeHeadsetSnapshot?) {
        val fresh = directory.publishSession(snapshot)
        val previous = lastPublished
        lastPublished = fresh
        if (fresh == null) {
            clearRememberedAncController()
            return
        }
        val rememberedReference = lastAncReference
        val rememberedAddress = lastAncAddress
        if (rememberedReference != null && (rememberedReference != fresh.reference || rememberedAddress !in fresh.addresses)) {
            clearRememberedAncController()
        }
        if (previous != null && !previous.sameReportedState(fresh) && fresh.matchesAddress(bluetoothAddress(lastAncDevice))) {
            publishChangedProperties(previous, fresh)
        }
    }

    private fun publishChangedProperties(previous: NativeHeadsetSnapshot, fresh: NativeHeadsetSnapshot) {
        val noiseChanged = previous.fields[DeviceField.NOISE_MODE] != fresh.fields[DeviceField.NOISE_MODE]
        val batteryChanged = BATTERY_STATE_FIELDS.any { field -> previous.fields[field] != fresh.fields[field] }
        val canUpdateBattery = SystemHeadsetMapping.miLinkSummaryBattery(fresh.fields) != null
        val controller = lastAncController?.get()
        val device = lastAncDevice
        if (noiseChanged) {
            if (SystemHeadsetMapping.miLinkAncState(fresh.fields) != null) {
                notifyHeadsetPropertyChanged(controller, device, UPDATE_ANC_STATE)
                if (canUpdateBattery) notifyHeadsetPropertyChanged(controller, device, UPDATE_HEADSET_PROPERTY)
            } else {
                report(FEATURE, "UNAVAILABLE", "noise state changed to a value MiLink cannot represent")
            }
        }
        if (batteryChanged && !canUpdateBattery) {
            report(FEATURE, "UNAVAILABLE", "battery state changed to values MiLink summary cannot represent")
        } else if (batteryChanged && !noiseChanged) {
            notifyHeadsetPropertyChanged(controller, device, UPDATE_HEADSET_PROPERTY)
        }
    }

    /** The module host's bonded ROSE earphones; identity queries answer for them even with no session (ADR 0017). */
    fun publishBonded(addresses: Set<String>) {
        if (!closed) directory.publishBonded(addresses)
    }

    /** Release every API 102 hook handle during module shutdown or hot reload. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        hookHandles.forEach { handle -> runCatching { handle.unhook() } }
        hookHandles.clear()
        installedIds.clear()
        lastPublished = null
        directory.clear()
        clearRememberedAncController()
        callbackHandler.removeCallbacksAndMessages(null)
    }

    private fun installMxBluetooth(className: String) {
        val target = loadClass(className) ?: return
        val bluetoothDevice = BluetoothDevice::class.java
        val string = String::class.java

        hook(target, "checkIsMiTWS", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            val address = bluetoothAddress(chain.args.getOrNull(0))
            if (!directory.identifies(address)) return@hook HookDecision.Proceed
            reportIdentified("checkIsMiTWS")
            HookDecision.Return(1)
        }
        hook(target, "getDeviceId", arrayOf(bluetoothDevice), string) { chain ->
            // This is the fixed MiLink compatibility identifier from the pinned adapter source;
            // it is deliberately separate from the ROSE profile/capability snapshot.
            identityAnswer(bluetoothAddress(chain.args.getOrNull(0)), NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        }
        hook(target, "getBatteryLevel", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            reportUnknown("getBatteryLevel_value_semantics_undocumented_preserving_native_result", snapshot)
            HookDecision.Proceed
        }
        hook(target, "getAncState", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            val mode = SystemHeadsetMapping.miLinkAncState(snapshot.fields)
            if (mode == null) {
                reportUnknown("noise_state_unrepresentable", snapshot)
                return@hook HookDecision.Proceed
            }
            HookDecision.Return(mode)
        }
        hook(target, "openAnc", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            command(chain, "anc", acceptedResult = 1)
        }
        hook(target, "closeAnc", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            command(chain, "normal", acceptedResult = 0)
        }
        hook(target, "openTransparent", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            command(chain, "transparency", acceptedResult = 2)
        }

        hook(target, "isMiTWS", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
            val address = chain.args.getOrNull(0) as? String
            if (!directory.identifies(address)) return@hook HookDecision.Proceed
            reportIdentified("isMiTWS")
            HookDecision.Return(true)
        }
        hook(target, "isSupportAudioSwitch", arrayOf(string), Int::class.javaPrimitiveType!!) { chain ->
            identityAnswer(chain.args.getOrNull(0) as? String, NativeHeadsetDirectory.MILINK_AUDIO_SWITCH)
        }
        hook(target, "getRingFindState", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
            snapshotForAddress(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
            // Hide the unsupported find-device entry for this exact managed address only.
            HookDecision.Return(false)
        }
        hook(target, "switchToHeadsetActivity", arrayOf(bluetoothDevice), Void.TYPE) { chain ->
            handleSwitchToHeadsetActivity(chain)
        }
    }

    private fun installProfileContext() {
        val target = loadClass("com.miui.headset.runtime.ProfileContext") ?: return
        hook(target, "getDeviceId", arrayOf(BluetoothDevice::class.java), String::class.java) { chain ->
            identityAnswer(bluetoothAddress(chain.args.getOrNull(0)), NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        }
        hook(target, "getBatteryLevel", arrayOf(BluetoothDevice::class.java), List::class.java) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            val levels = SystemHeadsetMapping.miLinkBattery(snapshot.fields)
            if (levels == null) {
                reportUnknown("battery_cache_unrepresentable", snapshot)
                return@hook HookDecision.Proceed
            }
            HookDecision.Return(levels)
        }
    }

    private fun installAncBatteryController() {
        val target = loadClass("com.miui.headset.runtime.AncBatteryController") ?: return
        val device = BluetoothDevice::class.java
        hook(target, "getDeviceId", arrayOf(device), String::class.java) { chain ->
            identityAnswer(bluetoothAddress(chain.args.getOrNull(0)), NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        }
        hook(target, "getAncState", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
            controllerReading(chain, "noise_state_unrepresentable") { SystemHeadsetMapping.miLinkAncState(it.fields) }
        }
        hook(target, "getBatteryLevelCache", arrayOf(device), List::class.java) { chain ->
            controllerReading(chain, "battery_cache_unrepresentable") { SystemHeadsetMapping.miLinkBattery(it.fields) }
        }
        hook(target, "getHeadsetPropertyBlock", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
            controllerReading(chain, "ear_battery_unavailable") { SystemHeadsetMapping.miLinkSummaryBattery(it.fields) }
        }
        hook(target, "getFindRingState", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
            snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            // -1 is the pinned MiLink source's explicit hidden/unsupported state.
            HookDecision.Return(-1)
        }
        hook(target, "getSwitchState", arrayOf(String::class.java), Int::class.javaPrimitiveType!!) { chain ->
            snapshotForAddress(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
            HookDecision.Return(0)
        }
        hook(target, "setAncStateBlock", arrayOf(device, Int::class.javaPrimitiveType!!), Int::class.javaPrimitiveType!!) { chain ->
            val targetDevice = chain.args.getOrNull(0)
            val address = bluetoothAddress(targetDevice)
            val snapshot = snapshotForDevice(targetDevice) ?: run {
                if (shouldSuppressManagedAction(address)) {
                    report(FEATURE, "UNAVAILABLE", "setAncStateBlock blocked because the managed address has no current session")
                    return@hook HookDecision.Return(-1)
                }
                return@hook HookDecision.Proceed
            }
            rememberAncController(chain, snapshot)
            val requestedMode = (chain.args.getOrNull(1) as? Int)?.let(SystemHeadsetMapping::noiseModeFromSystem)
            if (requestedMode == null) {
                report(FEATURE, "UNSUPPORTED", "setAncStateBlock received an unmapped MiLink mode")
                return@hook HookDecision.Return(SystemHeadsetMapping.miLinkAncState(snapshot.fields) ?: -1)
            }
            val confirmedMode = SystemHeadsetMapping.miLinkAncState(snapshot.fields) ?: -1
            submit(snapshot, requestedMode, address)
            // This setter returns a state code in the pinned source. Keep it at the last confirmed
            // mode; the app's device reply updates it through the next published snapshot.
            HookDecision.Return(confirmedMode)
        }
    }

    private fun controllerReading(
        chain: XposedInterface.Chain,
        unavailableReason: String,
        read: (NativeHeadsetSnapshot) -> Any?,
    ): HookDecision {
        val snapshot = snapshotForDevice(chain.args.firstOrNull()) ?: return HookDecision.Proceed
        rememberAncController(chain, snapshot)
        val value = read(snapshot)
        if (value != null) return HookDecision.Return(value)
        reportUnknown(unavailableReason, snapshot)
        return HookDecision.Proceed
    }

    private fun installHeadsetInfo() {
        val target = loadClass("com.miui.headset.api.HeadsetInfo") ?: return
        hookNoArgIdentity(target, "getDeviceId", String::class.java, NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        hookNoArgIdentity(target, "component3", String::class.java, NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        hookNoArg(target, "getPowers", List::class.java) { snapshot ->
            SystemHeadsetMapping.miLinkBattery(snapshot.fields)
        }
        hookNoArg(target, "component4", List::class.java) { snapshot ->
            SystemHeadsetMapping.miLinkBattery(snapshot.fields)
        }
        hookNoArg(target, "getMode", Int::class.javaPrimitiveType!!) { snapshot ->
            SystemHeadsetMapping.miLinkAncState(snapshot.fields)
        }
        hookNoArg(target, "component5", Int::class.javaPrimitiveType!!) { snapshot ->
            SystemHeadsetMapping.miLinkAncState(snapshot.fields)
        }
        hookNoArg(target, "getSwitchState", Int::class.javaPrimitiveType!!) { 0 }
        hookNoArg(target, "component8", Int::class.javaPrimitiveType!!) { 0 }
        hookNoArg(target, "getFindRingState", Int::class.javaPrimitiveType!!) { -1 }
        hookNoArg(target, "component11", Int::class.javaPrimitiveType!!) { -1 }
    }

    private fun hookNoArg(
        target: Class<*>,
        methodName: String,
        returnType: Class<*>,
        value: (NativeHeadsetSnapshot) -> Any?,
    ) = hookHeadsetInfo(target, methodName, returnType) { address ->
        val snapshot = snapshotForAddress(address) ?: return@hookHeadsetInfo HookDecision.Proceed
        val mapped = value(snapshot)
        if (mapped == null) {
            reportUnknown("${methodName}_unavailable", snapshot)
            HookDecision.Proceed
        } else {
            HookDecision.Return(mapped)
        }
    }

    private fun hookNoArgIdentity(target: Class<*>, methodName: String, returnType: Class<*>, value: Any) {
        hookHeadsetInfo(target, methodName, returnType) { address -> identityAnswer(address, value) }
    }

    /** Hooks a no-argument `HeadsetInfo` accessor, deciding from the address of the instance it reads. */
    private fun hookHeadsetInfo(
        target: Class<*>,
        methodName: String,
        returnType: Class<*>,
        decide: (String) -> HookDecision,
    ) {
        val method = resolveMethod(target, methodName, emptyArray()) ?: run {
            reportResolution(target.name, methodName, "METHOD_MISSING")
            return
        }
        if (method.returnType != returnType) {
            reportResolution(target.name, methodName, "RETURN_TYPE_MISMATCH")
            return
        }
        install(method) { chain ->
            val address = headsetInfoAddress(chain.thisObject) ?: return@install HookDecision.Proceed
            decide(address)
        }
    }

    private fun command(chain: XposedInterface.Chain, mode: String, acceptedResult: Int): HookDecision {
        val device = chain.args.getOrNull(0)
        val address = bluetoothAddress(device)
        val snapshot = snapshotForDevice(device) ?: run {
            if (shouldSuppressManagedAction(address)) {
                report(FEATURE, "UNAVAILABLE", "${chain.executable.name} blocked because the managed address has no current session")
                return HookDecision.Return(-1)
            }
            return HookDecision.Proceed
        }
        if (!snapshot.supportsNoise(mode)) {
            report(FEATURE, "UNSUPPORTED", "${chain.executable.name} is not in the current ROSE noise capability")
            return HookDecision.Return(-1)
        }
        return if (submit(snapshot, mode, address)) HookDecision.Return(acceptedResult) else HookDecision.Return(-1)
    }

    /** The callback only enqueues work; its Boolean means locally accepted, never device-confirmed. */
    private fun submit(snapshot: NativeHeadsetSnapshot, mode: String, address: String?): Boolean {
        if (!snapshot.isFresh(SystemClock.elapsedRealtime()) || !snapshot.supportsNoise(mode)) {
            report(FEATURE, "UNSUPPORTED", "mode=$mode is not currently supported by this session")
            return false
        }
        val normalizedAddress = normalizeBluetoothAddress(address)
            ?.takeIf(snapshot.addresses::contains) ?: run {
            report(FEATURE, "UNAVAILABLE", "mode=$mode request address is not in the authenticated snapshot")
            return false
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
        return result != SystemRequestGate.Result.REJECTED
    }

    private fun snapshotForDevice(value: Any?): NativeHeadsetSnapshot? = (value as? BluetoothDevice)?.let { device ->
        runCatching { snapshotForAddress(device.address) }.getOrNull()
    }

    private fun snapshotForAddress(address: String?): NativeHeadsetSnapshot? = directory.session(address)

    /** An identity query: answered for the current session and the bonded ROSE earphones, otherwise left to the system. */
    private fun identityAnswer(address: String?, value: Any): HookDecision {
        if (!directory.identifies(address)) return HookDecision.Proceed
        return HookDecision.Return(value)
    }

    private fun reportIdentified(query: String) = report(FEATURE, "IDENTIFIED", "$query identified ROSE earphones")

    private fun bluetoothAddress(value: Any?): String? = (value as? BluetoothDevice)
        ?.let { device -> runCatching { device.address }.getOrNull() }

    private fun shouldSuppressManagedAction(address: String?): Boolean = directory.isManaged(address) &&
        snapshotForAddress(address) == null

    private fun handleSwitchToHeadsetActivity(chain: XposedInterface.Chain): HookDecision {
        val device = chain.args.getOrNull(0) as? BluetoothDevice ?: return HookDecision.Proceed
        val normalizedAddress = runCatching { device.address }
            .getOrNull()
            ?.let(::normalizeBluetoothAddress)
            ?: return HookDecision.Proceed

        // The module host decides from its own persisted destination and the earphones it owns; the answer
        // never depends on the application process. Any doubt leaves the system's own settings page alone.
        val canonicalDeviceId = callbacks.fusionEntryDevice(normalizedAddress) ?: return HookDecision.Proceed

        val context = contextSupplier?.invoke()
            ?: resolveContext(chain.thisObject)
            ?: run {
                module.log(Log.WARN, TAG, "switchToHeadsetActivity context resolution failed for $canonicalDeviceId")
                return HookDecision.Proceed
            }

        val launchIntent = Intent(BridgeWire.ACTION_SHOW_HEADPHONES).apply {
            setClassName(BridgeWire.APP_PACKAGE, "${BridgeWire.APP_PACKAGE}.MainActivity")
            putExtra(BridgeWire.EXTRA_HEADPHONE_ADDRESS, canonicalDeviceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        return try {
            context.startActivity(launchIntent)
            module.log(Log.INFO, TAG, "switchToHeadsetActivity redirected to OpenRose target=$canonicalDeviceId")
            report(FEATURE, "CONFIRMED", "switchToHeadsetActivity redirected to OpenRose for $canonicalDeviceId")
            HookDecision.Return(null)
        } catch (failure: Throwable) {
            module.log(Log.WARN, TAG, "switchToHeadsetActivity failed to launch OpenRose for $canonicalDeviceId", failure)
            report(FEATURE, "ACTION_FAILED", "switchToHeadsetActivity launch failed for $canonicalDeviceId: ${failure.message}")
            HookDecision.Proceed
        }
    }

    private fun resolveContext(obj: Any?): Context? {
        if (obj == null) return null
        if (obj is Context) return obj
        return runCatching {
            val field = obj.javaClass.getDeclaredField("mContext").apply { isAccessible = true }
            field.get(obj) as? Context
        }.getOrNull()
    }
    private fun headsetInfoAddress(info: Any?): String? {
        if (info == null) return null
        return listOf("getAddress", "component1").firstNotNullOfOrNull { name ->
            val method = resolveMethod(info.javaClass, name, emptyArray())
            if (method?.returnType == String::class.java) {
                runCatching { method.invoke(info) as? String }.getOrNull()?.takeIf { normalizeBluetoothAddress(it) != null }
            } else {
                null
            }
        }
    }

    private fun loadClass(className: String): Class<*>? = try {
        classLoader.loadClass(className)
    } catch (failure: Throwable) {
        module.log(Log.WARN, TAG, "resolution_class_missing class=$className", failure)
        report(FEATURE, "UNAVAILABLE", "class_missing:$className")
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
        install(method, handler)
    }

    private fun install(method: Method, handler: (XposedInterface.Chain) -> HookDecision) {
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
            hookHandles += handle
        } catch (failure: Throwable) {
            installedIds.remove(id)
            module.log(Log.ERROR, TAG, "hook_install_failed class=${method.declaringClass.name} method=${method.name}", failure)
            report(FEATURE, "UNAVAILABLE", "hook_failed:${method.declaringClass.name}.${method.name}")
        }
    }

    private fun resolveMethod(target: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method? {
        var current: Class<*>? = target
        while (current != null) {
            val match = current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.contentEquals(parameterTypes)
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
        runCatching { callbacks.report(reportFeature.take(64), componentStatus(status), detail.take(512)) }
            .onFailure { module.log(Log.WARN, TAG, "diagnostic_callback_failed feature=$feature status=$status", it) }
    }

    private fun componentStatus(status: String): String = when (status) {
        in BridgePresentationWire.COMPONENT_STATUSES -> status
        else -> BridgePresentationWire.ACTION_FAILED
    }

    private fun reportUnknown(reason: String, snapshot: NativeHeadsetSnapshot) {
        report(FEATURE, "UNAVAILABLE", "$reason session=${snapshot.sessionId}")
    }

    private fun rememberAncController(chain: XposedInterface.Chain, snapshot: NativeHeadsetSnapshot) {
        val controller = chain.thisObject ?: return
        val device = chain.args.firstOrNull() as? BluetoothDevice ?: return
        val address = normalizeBluetoothAddress(runCatching { device.address }.getOrNull()) ?: return
        if (!snapshot.matchesAddress(address)) return
        lastAncController = WeakReference(controller)
        lastAncDevice = device
        lastAncReference = snapshot.reference
        lastAncAddress = address
    }

    private fun clearRememberedAncController() {
        lastAncController = null
        lastAncDevice = null
        lastAncReference = null
        lastAncAddress = null
    }

    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice?, updateType: Int) {
        if (controller == null || device == null) return
        val address = normalizeBluetoothAddress(bluetoothAddress(device)) ?: return
        val expectedReference = lastAncReference ?: return
        fun isCurrentController(): Boolean {
            val sameSession = lastAncReference == expectedReference && lastAncAddress == address
            val sameController = lastAncController?.get() === controller && lastAncDevice === device
            return sameSession && sameController && directory.session(address)?.reference == expectedReference
        }
        if (!isCurrentController()) return
        callbackHandler.post {
            if (closed || !isCurrentController()) return@post
            if (normalizeBluetoothAddress(bluetoothAddress(device)) != address) return@post
            invokePropertyChangeListener(controller, device, updateType)
        }
    }

    private fun invokePropertyChangeListener(controller: Any, device: BluetoothDevice, updateType: Int) {
        try {
            val listener = findObjectField(controller, "headsetPropertyChangeListener") ?: return
            val invoke = findMethodByNameAndCount(listener.javaClass, "invoke", 2)
                ?: throw NoSuchMethodException("headsetPropertyChangeListener.invoke/2")
            invoke.invoke(listener, device, updateType)
        } catch (failure: Throwable) {
            module.log(Log.WARN, TAG, "headset_property_callback_failed updateType=$updateType", failure)
            report(FEATURE, "UNAVAILABLE", "headset_property_callback_failed:$updateType")
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

    private fun hookId(method: Method): String = "openrose.native_headset.milink.${method.declaringClass.name}.${method.name}"
        .replace('-', '_')

    private sealed interface HookDecision {
        data object Proceed : HookDecision
        data class Return(val value: Any?) : HookDecision
    }

    private companion object {
        const val TAG = "OpenRose-MiLink"
        const val FEATURE = "FUSION_CENTER"
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

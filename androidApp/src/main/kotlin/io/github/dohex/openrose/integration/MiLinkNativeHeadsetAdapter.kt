package io.github.dohex.openrose.integration

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.dohex.openrose.bridge.BridgePresentationWire
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
 * which queues a mode request into the same ControlSession.
 */
class MiLinkNativeHeadsetAdapter(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
    private val callbacks: AuthenticatedNativeHeadsetCallbacks,
    private val reportFeature: String = BridgePresentationWire.FUSION_CENTER,
) {
    init {
        require(reportFeature == BridgePresentationWire.FUSION_CENTER || reportFeature == BridgePresentationWire.SYSTEM_HEADSET)
    }

    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val installedIds = mutableSetOf<String>()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val managedAddresses = NativeManagedAddressTombstones()
    @Volatile private var currentSnapshot: NativeHeadsetSnapshot? = null
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
        val fresh = snapshot?.takeIf { it.isFresh() }
        fresh?.let(managedAddresses::remember)
        val previous = currentSnapshot
        currentSnapshot = fresh
        if (fresh == null) {
            clearRememberedAncController()
            return
        }
        val rememberedReference = lastAncReference
        val rememberedAddress = lastAncAddress
        if (rememberedReference != null &&
            (rememberedReference != fresh.reference || rememberedAddress == null || rememberedAddress !in fresh.addresses)
        ) {
            clearRememberedAncController()
        }
        if (previous == null || previous.sameReportedState(fresh)) return
        val address = lastAncDevice?.let { runCatching { it.address }.getOrNull() }
        if (!fresh.matchesAddress(address)) return

        val noiseChanged = previous.fields["NOISE_MODE"] != fresh.fields["NOISE_MODE"]
        val batteryChanged = BATTERY_STATE_FIELDS.any { field -> previous.fields[field] != fresh.fields[field] }
        val canUpdateBattery = hasSummaryBattery(fresh)
        val controller = lastAncController?.get()
        val device = lastAncDevice
        if (noiseChanged) {
            if (fresh.knownText("NOISE_MODE")?.let(::miLinkModeFromRose) != null) {
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

    /** Release every API 102 hook handle during module shutdown or hot reload. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        hookHandles.forEach { handle -> runCatching { handle.unhook() } }
        hookHandles.clear()
        installedIds.clear()
        currentSnapshot = null
        managedAddresses.clear()
        clearRememberedAncController()
        callbackHandler.removeCallbacksAndMessages(null)
    }

    private fun installMxBluetooth(className: String) {
        val target = loadClass(className) ?: return
        val bluetoothDevice = BluetoothDevice::class.java
        val string = String::class.java

        hook(target, "checkIsMiTWS", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            report(FEATURE, "IDENTIFIED", "checkIsMiTWS matched ${snapshot.profile}")
            HookDecision.Return(1)
        }
        hook(target, "getDeviceId", arrayOf(bluetoothDevice), string) { chain ->
            snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            // This is the fixed MiLink compatibility identifier from the pinned adapter source;
            // it is deliberately separate from the ROSE profile/capability snapshot.
            HookDecision.Return(NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        }
        hook(target, "getBatteryLevel", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            reportUnknown("getBatteryLevel_value_semantics_undocumented_preserving_native_result", snapshot)
            HookDecision.Proceed
        }
        hook(target, "getAncState", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            val mode = snapshot.knownText("NOISE_MODE")?.let(::miLinkModeFromRose)
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
            val snapshot = snapshotForAddress(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
            report(FEATURE, "IDENTIFIED", "isMiTWS matched ${snapshot.profile}")
            HookDecision.Return(true)
        }
        hook(target, "isSupportAudioSwitch", arrayOf(string), Int::class.javaPrimitiveType!!) { chain ->
            snapshotForAddress(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
            // The pinned source uses 0 when the spatial/audio switch panel is disabled.
            HookDecision.Return(0)
        }
        hook(target, "getRingFindState", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
            snapshotForAddress(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
            // Hide the unsupported find-device entry for this exact managed address only.
            HookDecision.Return(false)
        }

    }

    private fun installProfileContext() {
        val target = loadClass("com.miui.headset.runtime.ProfileContext") ?: return
        hook(target, "getDeviceId", arrayOf(BluetoothDevice::class.java), String::class.java) { chain ->
            snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            HookDecision.Return(NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        }
        hook(target, "getBatteryLevel", arrayOf(BluetoothDevice::class.java), List::class.java) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            val levels = batteryCache(snapshot)
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
            snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            HookDecision.Return(NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
        }
        hook(target, "getAncState", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            rememberAncController(chain, snapshot)
            val mode = snapshot.knownText("NOISE_MODE")?.let(::miLinkModeFromRose)
            if (mode == null) {
                reportUnknown("noise_state_unrepresentable", snapshot)
                return@hook HookDecision.Proceed
            }
            HookDecision.Return(mode)
        }
        hook(target, "getBatteryLevelCache", arrayOf(device), List::class.java) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            rememberAncController(chain, snapshot)
            val levels = batteryCache(snapshot)
            if (levels == null) {
                reportUnknown("battery_cache_unrepresentable", snapshot)
                return@hook HookDecision.Proceed
            }
            HookDecision.Return(levels)
        }
        hook(target, "getHeadsetPropertyBlock", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
            val snapshot = snapshotForDevice(chain.args.getOrNull(0)) ?: return@hook HookDecision.Proceed
            rememberAncController(chain, snapshot)
            val left = snapshot.knownNumber("LEFT_BATTERY")?.takeIf { it in 0..100 }
            val right = snapshot.knownNumber("RIGHT_BATTERY")?.takeIf { it in 0..100 }
            if (left == null || right == null) {
                reportUnknown("ear_battery_unavailable", snapshot)
                return@hook HookDecision.Proceed
            }
            HookDecision.Return(minOf(left, right))
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
            val requestedMode = (chain.args.getOrNull(1) as? Int)?.let(::roseModeFromMiLink)
            if (requestedMode == null) {
                report(FEATURE, "UNSUPPORTED", "setAncStateBlock received an unmapped MiLink mode")
                return@hook HookDecision.Return(snapshot.knownText("NOISE_MODE")?.let(::miLinkModeFromRose) ?: -1)
            }
            val confirmedMode = snapshot.knownText("NOISE_MODE")?.let(::miLinkModeFromRose) ?: -1
            submit(snapshot, requestedMode, address)
            // This setter returns a state code in the pinned source. Keep it at the last confirmed
            // mode; the app's device reply updates it through the next published snapshot.
            HookDecision.Return(confirmedMode)
        }
    }

    private fun installHeadsetInfo() {
        val target = loadClass("com.miui.headset.api.HeadsetInfo") ?: return
        hookNoArg(target, "getDeviceId", String::class.java) { snapshot ->
            NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID
        }
        hookNoArg(target, "component3", String::class.java) { snapshot ->
            NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID
        }
        hookNoArg(target, "getPowers", List::class.java) { snapshot ->
            batteryCache(snapshot)
        }
        hookNoArg(target, "component4", List::class.java) { snapshot ->
            batteryCache(snapshot)
        }
        hookNoArg(target, "getMode", Int::class.javaPrimitiveType!!) { snapshot ->
            snapshot.knownText("NOISE_MODE")?.let(::miLinkModeFromRose)
        }
        hookNoArg(target, "component5", Int::class.javaPrimitiveType!!) { snapshot ->
            snapshot.knownText("NOISE_MODE")?.let(::miLinkModeFromRose)
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
            val snapshot = snapshotForAddress(address) ?: return@install HookDecision.Proceed
            val mapped = value(snapshot)
            if (mapped == null) {
                reportUnknown("${methodName}_unavailable", snapshot)
                HookDecision.Proceed
            } else {
                HookDecision.Return(mapped)
            }
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
        if (!snapshot.isFresh() || !snapshot.supportsNoise(mode)) {
            report(FEATURE, "UNSUPPORTED", "mode=$mode is not currently supported by this session")
            return false
        }
        val normalizedAddress = NativeHeadsetSnapshot.normalizeBluetoothAddress(address)
            ?.takeIf(snapshot.addresses::contains) ?: run {
                report(FEATURE, "UNAVAILABLE", "mode=$mode request address is not in the authenticated snapshot")
                return false
            }
        val result = NativeNoiseRequestGate.submit(snapshot, mode) {
            val current = currentSnapshot?.takeIf {
                !closed && it.isFresh() && it.reference == snapshot.reference &&
                    it.matchesAddress(normalizedAddress) && it.supportsNoise(mode)
            }
            if (current == null) false else callbacks.submitNoise(current, mode)
        }
        if (result == NativeNoiseRequestGate.Result.REJECTED) {
            module.log(Log.WARN, TAG, "native noise dispatch rejected mode=$mode")
        }
        report(
            FEATURE,
            if (result == NativeNoiseRequestGate.Result.REJECTED) "UNAVAILABLE" else "SUBMITTED",
            when (result) {
                NativeNoiseRequestGate.Result.ACCEPTED -> "mode=$mode session=${snapshot.sessionId}; awaiting device confirmation"
                NativeNoiseRequestGate.Result.DUPLICATE -> "mode=$mode session=${snapshot.sessionId}; duplicate vendor callback coalesced"
                NativeNoiseRequestGate.Result.REJECTED -> "mode=$mode session=${snapshot.sessionId}; authenticated control callback unavailable"
            },
        )
        return result != NativeNoiseRequestGate.Result.REJECTED
    }

    private fun batteryCache(snapshot: NativeHeadsetSnapshot): List<Int>? {
        val left = snapshot.knownNumber("LEFT_BATTERY")
        val right = snapshot.knownNumber("RIGHT_BATTERY")
        val case = snapshot.knownNumber("CASE_BATTERY")
        val leftCharging = snapshot.knownToggle("LEFT_CHARGING")
        val rightCharging = snapshot.knownToggle("RIGHT_CHARGING")
        val caseCharging = snapshot.knownToggle("CASE_CHARGING")
        // The fixed MiLink list includes case charging, but the current core wire does not provide
        // CASE_CHARGING. Do not turn that missing fact into the source's 0 (not charging).
        if (caseCharging == null || leftCharging == null || rightCharging == null) return null
        if (left == null || right == null || case == null) return null
        if (left !in 0..100 || right !in 0..100 || case !in 0..100) return null
        return listOf(
            case,
            left,
            right,
            if (caseCharging) 1 else 0,
            if (leftCharging) 1 else 0,
            if (rightCharging) 1 else 0,
        )
    }

    private fun snapshotForDevice(value: Any?): NativeHeadsetSnapshot? =
        (value as? BluetoothDevice)?.let { device ->
            runCatching { snapshotForAddress(device.address) }.getOrNull()
        }

    private fun snapshotForAddress(address: String?): NativeHeadsetSnapshot? {
        val snapshot = currentSnapshot?.takeIf { it.isFresh() } ?: return null
        return snapshot.takeIf { it.matchesAddress(address) }
    }

    private fun bluetoothAddress(value: Any?): String? =
        (value as? BluetoothDevice)?.let { device -> runCatching { device.address }.getOrNull() }

    private fun shouldSuppressManagedAction(address: String?): Boolean =
        managedAddresses.contains(address) && snapshotForAddress(address) == null

    private fun headsetInfoAddress(info: Any?): String? {
        if (info == null) return null
        for (name in listOf("getAddress", "component1")) {
            val method = resolveMethod(info.javaClass, name, emptyArray()) ?: continue
            if (method.returnType != String::class.java) continue
            val address = runCatching { method.invoke(info) as? String }.getOrNull()
            if (NativeHeadsetSnapshot.normalizeBluetoothAddress(address) != null) return address
        }
        return null
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

    private fun hasSummaryBattery(snapshot: NativeHeadsetSnapshot): Boolean =
        snapshot.knownNumber("LEFT_BATTERY")?.let { it in 0..100 } == true &&
            snapshot.knownNumber("RIGHT_BATTERY")?.let { it in 0..100 } == true

    private fun rememberAncController(chain: XposedInterface.Chain, snapshot: NativeHeadsetSnapshot) {
        val controller = chain.thisObject ?: return
        val device = chain.args.firstOrNull() as? BluetoothDevice ?: return
        val address = NativeHeadsetSnapshot.normalizeBluetoothAddress(runCatching { device.address }.getOrNull()) ?: return
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
        val address = NativeHeadsetSnapshot.normalizeBluetoothAddress(runCatching { device.address }.getOrNull())
            ?: return
        val expectedReference = lastAncReference ?: return
        if (lastAncAddress != address) return
        if (currentSnapshot?.takeIf {
            it.isFresh() && it.reference == expectedReference && it.matchesAddress(address)
        } == null) return
        if (lastAncController?.get() !== controller || lastAncDevice !== device) return
        callbackHandler.post {
            val current = currentSnapshot?.takeIf { !closed && it.isFresh() }
            if (current == null || current.reference != expectedReference || !current.matchesAddress(address) ||
                lastAncReference != expectedReference || lastAncAddress != address ||
                lastAncController?.get() !== controller || lastAncDevice !== device
            ) return@post
            if (NativeHeadsetSnapshot.normalizeBluetoothAddress(runCatching { device.address }.getOrNull()) != address) return@post
            try {
                val listener = findObjectField(controller, "headsetPropertyChangeListener") ?: return@post
                val invoke = findMethodByNameAndCount(listener.javaClass, "invoke", 2)
                    ?: throw NoSuchMethodException("headsetPropertyChangeListener.invoke/2")
                invoke.invoke(listener, device, updateType)
            } catch (failure: Throwable) {
                module.log(Log.WARN, TAG, "headset_property_callback_failed updateType=$updateType", failure)
                report(FEATURE, "UNAVAILABLE", "headset_property_callback_failed:$updateType")
            }
        }
    }

    private fun findObjectField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) return runCatching { field.isAccessible = true; field.get(instance) }.getOrNull()
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

    private fun hookId(method: Method): String =
        "openrose.native_headset.milink.${method.declaringClass.name}.${method.name}".replace('-', '_')

    private fun miLinkModeFromRose(mode: String): Int? = when (mode.trim().lowercase()) {
        "normal" -> 0
        "anc" -> 1
        "transparency" -> 2
        else -> null
    }

    private fun roseModeFromMiLink(mode: Int): String? = when (mode) {
        0 -> "normal"
        1 -> "anc"
        2 -> "transparency"
        else -> null
    }

    private sealed interface HookDecision {
        data object Proceed : HookDecision
        data class Return(val value: Any?) : HookDecision
    }

    private companion object {
        const val TAG = "OpenRose-MiLink"
        const val FEATURE = "FUSION_CENTER"
        const val UPDATE_ANC_STATE = 8
        const val UPDATE_HEADSET_PROPERTY = 4
        val BATTERY_STATE_FIELDS = setOf("LEFT_BATTERY", "RIGHT_BATTERY", "CASE_BATTERY", "LEFT_CHARGING", "RIGHT_CHARGING")
    }
}

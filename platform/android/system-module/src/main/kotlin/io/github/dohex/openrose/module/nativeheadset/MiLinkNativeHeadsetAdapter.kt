package io.github.dohex.openrose.module.nativeheadset

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

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
    classLoader: ClassLoader,
    private val callbacks: AuthenticatedNativeHeadsetCallbacks,
    reportFeature: String = BridgePresentationWire.FUSION_CENTER,
    private val contextSupplier: (() -> Context?)? = null,
) {
    init {
        require(reportFeature == BridgePresentationWire.FUSION_CENTER || reportFeature == BridgePresentationWire.SYSTEM_HEADSET)
    }

    private val hooks = NativeHeadsetHooks(module, classLoader, callbacks, reportFeature, TAG, "milink")
    private val directory = NativeHeadsetDirectory(SystemClock::elapsedRealtime)
    private val updates = MiLinkPropertyUpdates(directory, hooks, module) { closed }
    private val deviceMethods = DeviceMethods()
    private val info = HeadsetInfo()

    @Volatile private var closed = false

    val handles: List<XposedInterface.HookHandle> get() = hooks.handles

    /** Install exact class and method signatures. Missing ROM APIs are reported individually. */
    @Synchronized
    fun install(): List<XposedInterface.HookHandle> {
        if (closed) return handles
        deviceMethods.installMxBluetooth("com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager")
        deviceMethods.installMxBluetooth("com.xiaomi.mxbluetoothsdk.service.MxBluetoothService")
        deviceMethods.installProfileContext()
        deviceMethods.installAncBatteryController()
        info.installHeadsetInfo()
        hooks.report(if (handles.isEmpty()) "UNAVAILABLE" else "RESOLVED", "MiLink method probes installed=${handles.size}")
        return handles
    }

    fun publish(snapshot: NativeHeadsetSnapshot?) = updates.publish(directory.publishSession(snapshot))

    /** The module host's bonded ROSE earphones; identity queries answer for them even with no session (ADR 0017). */
    fun publishBonded(addresses: Set<String>) {
        if (!closed) directory.publishBonded(addresses)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        hooks.close()
        directory.clear()
        updates.close()
    }

    private fun submit(snapshot: NativeHeadsetSnapshot, mode: String, address: String?): Boolean {
        val current = { if (closed) null else directory.current() }
        return hooks.submitNoise(snapshot, mode, address, current)
    }

    /** An identity query: answered for the current session and the bonded ROSE earphones, otherwise left to the system. */
    private fun identityAnswer(address: String?, value: Any): HookDecision {
        if (!directory.identifies(address)) return HookDecision.Proceed
        return HookDecision.Return(value)
    }

    private fun reportIdentified(query: String) = hooks.report("IDENTIFIED", "$query identified ROSE earphones")

    private fun reportUnknown(reason: String, snapshot: NativeHeadsetSnapshot) {
        hooks.report("UNAVAILABLE", "$reason session=${snapshot.sessionId}")
    }

    /** With no session answering, a managed address must not fall through to the vendor's own control path. */
    private fun shouldSuppressManagedAction(address: String?): Boolean = directory.isManaged(address)

    private inner class DeviceMethods {
        fun installMxBluetooth(className: String) {
            val target = hooks.loadClass(className) ?: return
            val bluetoothDevice = BluetoothDevice::class.java
            val string = String::class.java

            hooks.hook(target, "checkIsMiTWS", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
                val address = bluetoothAddress(chain.args.getOrNull(0))
                if (!directory.identifies(address)) return@hook HookDecision.Proceed
                reportIdentified("checkIsMiTWS")
                HookDecision.Return(1)
            }
            hooks.hook(target, "getDeviceId", arrayOf(bluetoothDevice), string) { chain ->
                // This is the fixed MiLink compatibility identifier from the pinned adapter source;
                // it is deliberately separate from the ROSE profile/capability snapshot.
                identityAnswer(bluetoothAddress(chain.args.getOrNull(0)), NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
            }
            hooks.hook(target, "getBatteryLevel", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
                val snapshot = directory.session(bluetoothAddress(chain.args.getOrNull(0))) ?: return@hook HookDecision.Proceed
                reportUnknown("getBatteryLevel_value_semantics_undocumented_preserving_native_result", snapshot)
                HookDecision.Proceed
            }
            hooks.hook(target, "getAncState", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
                val snapshot = directory.session(bluetoothAddress(chain.args.getOrNull(0))) ?: return@hook HookDecision.Proceed
                val mode = SystemHeadsetMapping.miLinkAncState(snapshot.fields)
                if (mode == null) {
                    reportUnknown("noise_state_unrepresentable", snapshot)
                    return@hook HookDecision.Proceed
                }
                HookDecision.Return(mode)
            }
            hooks.hook(target, "openAnc", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
                command(chain, "anc", acceptedResult = 1)
            }
            hooks.hook(target, "closeAnc", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
                command(chain, "normal", acceptedResult = 0)
            }
            hooks.hook(target, "openTransparent", arrayOf(bluetoothDevice), Int::class.javaPrimitiveType!!) { chain ->
                command(chain, "transparency", acceptedResult = 2)
            }

            hooks.hook(target, "isMiTWS", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
                val address = chain.args.getOrNull(0) as? String
                if (!directory.identifies(address)) return@hook HookDecision.Proceed
                reportIdentified("isMiTWS")
                HookDecision.Return(true)
            }
            hooks.hook(target, "isSupportAudioSwitch", arrayOf(string), Int::class.javaPrimitiveType!!) { chain ->
                identityAnswer(chain.args.getOrNull(0) as? String, NativeHeadsetDirectory.MILINK_AUDIO_SWITCH)
            }
            hooks.hook(target, "getRingFindState", arrayOf(string), Boolean::class.javaPrimitiveType!!) { chain ->
                directory.session(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
                // Hide the unsupported find-device entry for this exact managed address only.
                HookDecision.Return(false)
            }
            hooks.hook(target, "switchToHeadsetActivity", arrayOf(bluetoothDevice), Void.TYPE) { chain ->
                handleSwitchToHeadsetActivity(chain)
            }
        }

        fun installProfileContext() {
            val target = hooks.loadClass("com.miui.headset.runtime.ProfileContext") ?: return
            hooks.hook(target, "getDeviceId", arrayOf(BluetoothDevice::class.java), String::class.java) { chain ->
                identityAnswer(bluetoothAddress(chain.args.getOrNull(0)), NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
            }
            hooks.hook(target, "getBatteryLevel", arrayOf(BluetoothDevice::class.java), List::class.java) { chain ->
                val snapshot = directory.session(bluetoothAddress(chain.args.getOrNull(0))) ?: return@hook HookDecision.Proceed
                val levels = SystemHeadsetMapping.miLinkBattery(snapshot.fields)
                if (levels == null) {
                    reportUnknown("battery_cache_unrepresentable", snapshot)
                    return@hook HookDecision.Proceed
                }
                HookDecision.Return(levels)
            }
        }

        fun installAncBatteryController() {
            val target = hooks.loadClass("com.miui.headset.runtime.AncBatteryController") ?: return
            val device = BluetoothDevice::class.java
            hooks.hook(target, "getDeviceId", arrayOf(device), String::class.java) { chain ->
                identityAnswer(bluetoothAddress(chain.args.getOrNull(0)), NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID)
            }
            hooks.hook(target, "getAncState", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
                controllerReading(chain, "noise_state_unrepresentable") { SystemHeadsetMapping.miLinkAncState(it.fields) }
            }
            hooks.hook(target, "getBatteryLevelCache", arrayOf(device), List::class.java) { chain ->
                controllerReading(chain, "battery_cache_unrepresentable") { SystemHeadsetMapping.miLinkBattery(it.fields) }
            }
            hooks.hook(target, "getHeadsetPropertyBlock", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
                controllerReading(chain, "ear_battery_unavailable") { SystemHeadsetMapping.miLinkSummaryBattery(it.fields) }
            }
            hooks.hook(target, "getFindRingState", arrayOf(device), Int::class.javaPrimitiveType!!) { chain ->
                directory.session(bluetoothAddress(chain.args.getOrNull(0))) ?: return@hook HookDecision.Proceed
                // -1 is the pinned MiLink source's explicit hidden/unsupported state.
                HookDecision.Return(-1)
            }
            hooks.hook(target, "getSwitchState", arrayOf(String::class.java), Int::class.javaPrimitiveType!!) { chain ->
                directory.session(chain.args.getOrNull(0) as? String) ?: return@hook HookDecision.Proceed
                HookDecision.Return(0)
            }
            val stateType = Int::class.javaPrimitiveType!!
            hooks.hook(target, "setAncStateBlock", arrayOf(device, stateType), stateType) { chain ->
                val targetDevice = chain.args.getOrNull(0)
                val address = bluetoothAddress(targetDevice)
                val snapshot = directory.session(bluetoothAddress(targetDevice)) ?: run {
                    if (shouldSuppressManagedAction(address)) {
                        hooks.report("UNAVAILABLE", "setAncStateBlock blocked because the managed address has no current session")
                        return@hook HookDecision.Return(-1)
                    }
                    return@hook HookDecision.Proceed
                }
                updates.remember(chain, snapshot)
                val requestedMode = (chain.args.getOrNull(1) as? Int)?.let(SystemHeadsetMapping::noiseModeFromSystem)
                if (requestedMode == null) {
                    hooks.report("UNSUPPORTED", "setAncStateBlock received an unmapped MiLink mode")
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
            val snapshot = directory.session(bluetoothAddress(chain.args.firstOrNull())) ?: return HookDecision.Proceed
            updates.remember(chain, snapshot)
            val value = read(snapshot)
            if (value != null) return HookDecision.Return(value)
            reportUnknown(unavailableReason, snapshot)
            return HookDecision.Proceed
        }

        private fun command(chain: XposedInterface.Chain, mode: String, acceptedResult: Int): HookDecision {
            val device = chain.args.getOrNull(0)
            val address = bluetoothAddress(device)
            val snapshot = directory.session(bluetoothAddress(device)) ?: run {
                if (shouldSuppressManagedAction(address)) {
                    hooks.report("UNAVAILABLE", "${chain.executable.name} blocked because the managed address has no current session")
                    return HookDecision.Return(-1)
                }
                return HookDecision.Proceed
            }
            return if (submit(snapshot, mode, address)) HookDecision.Return(acceptedResult) else HookDecision.Return(-1)
        }

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

            return hostAdapterCall(onFailure = { failure ->
                module.log(Log.WARN, TAG, "switchToHeadsetActivity failed to launch OpenRose for $canonicalDeviceId", failure)
                hooks.report("ACTION_FAILED", "switchToHeadsetActivity launch failed for $canonicalDeviceId: ${failure.message}")
                HookDecision.Proceed
            }) {
                context.startActivity(launchIntent)
                module.log(Log.INFO, TAG, "switchToHeadsetActivity redirected to OpenRose target=$canonicalDeviceId")
                hooks.report("CONFIRMED", "switchToHeadsetActivity redirected to OpenRose for $canonicalDeviceId")
                HookDecision.Return(null)
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
    }

    private inner class HeadsetInfo {
        fun installHeadsetInfo() {
            val target = hooks.loadClass("com.miui.headset.api.HeadsetInfo") ?: return
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
            val snapshot = directory.session(address) ?: return@hookHeadsetInfo HookDecision.Proceed
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
            hooks.hook(target, methodName, emptyArray(), returnType) { chain ->
                val address = headsetInfoAddress(chain.thisObject) ?: return@hook HookDecision.Proceed
                decide(address)
            }
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
    }

    private companion object {
        const val TAG = "OpenRose-MiLink"
    }
}

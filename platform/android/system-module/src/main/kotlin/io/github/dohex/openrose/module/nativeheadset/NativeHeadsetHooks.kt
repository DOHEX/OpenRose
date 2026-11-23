package io.github.dohex.openrose.module.nativeheadset

import android.bluetooth.BluetoothDevice
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemRequestGate
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal sealed interface HookDecision {
    data object Proceed : HookDecision
    data class Return(val value: Any?) : HookDecision
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> hostAdapterCall(onFailure: (Throwable) -> T, call: () -> T): T = try {
    call()
} catch (failure: Throwable) {
    onFailure(failure)
}

internal fun resolveMethod(target: Class<*>, name: String, parameters: Array<Class<*>>): Method? {
    var type: Class<*>? = target
    while (type != null) {
        val method = type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(parameters) }
        if (method != null) return runCatching { method.apply { isAccessible = true } }.getOrNull()
        type = type.superclass
    }
    return null
}

internal fun bluetoothAddress(value: Any?): String? = (value as? BluetoothDevice)?.let { runCatching { it.address }.getOrNull() }

internal class NativeHeadsetHooks(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val callbacks: AuthenticatedNativeHeadsetCallbacks,
    private val feature: String,
    private val tag: String,
    private val idPrefix: String,
) {
    private val registered = mutableMapOf<String, XposedInterface.HookHandle>()
    private var closed = false
    val handles: List<XposedInterface.HookHandle>
        @Synchronized get() = registered.values.toList()

    fun loadClass(name: String): Class<*>? = hostAdapterCall(onFailure = { failure ->
        module.log(Log.WARN, tag, "resolution_class_missing class=$name", failure)
        report("UNAVAILABLE", "class_missing:$name")
        null
    }) { loader.loadClass(name) }

    fun hook(
        target: Class<*>,
        name: String,
        parameters: Array<Class<*>>,
        resultType: Class<*>,
        handler: (XposedInterface.Chain) -> HookDecision,
    ) {
        val method = resolveMethod(target, name, parameters)
        when {
            method == null -> reportResolution(target.name, name, "METHOD_MISSING")
            method.returnType != resultType -> reportResolution(target.name, name, "RETURN_TYPE_MISMATCH")
            else -> installDecision(method, handler)
        }
    }

    fun installDecision(method: Method, handler: (XposedInterface.Chain) -> HookDecision) = install(method) { chain ->
        val decision = hostAdapterCall(onFailure = { failure ->
            handlerFailed(method, "hook_handler_failed", failure)
            HookDecision.Proceed
        }) { handler(chain) }
        if (decision is HookDecision.Return) decision.value else chain.proceed()
    }

    fun installAfter(method: Method, after: (Any?) -> Unit) = install(method) { chain ->
        val result = chain.proceed()
        hostAdapterCall(onFailure = { failure -> handlerFailed(method, "hook_after_failed", failure) }) { after(result) }
        result
    }

    @Synchronized
    private fun install(method: Method, intercept: (XposedInterface.Chain) -> Any?) {
        val id = "openrose.native_headset.$idPrefix.${method.declaringClass.name}.${method.name}".replace('-', '_')
        if (closed || id in registered) return
        hostAdapterCall(onFailure = { failure ->
            module.log(Log.ERROR, tag, "hook_install_failed class=${method.declaringClass.name} method=${method.name}", failure)
            report("UNAVAILABLE", "hook_failed:${method.declaringClass.name}.${method.name}")
        }) {
            registered[id] = module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> intercept(chain) }
        }
    }

    private fun handlerFailed(method: Method, reason: String, failure: Throwable) {
        module.log(Log.WARN, tag, "$reason class=${method.declaringClass.name} method=${method.name}", failure)
        report("UNAVAILABLE", "$reason:${method.declaringClass.name}.${method.name}")
    }

    fun reportResolution(className: String, method: String, status: String) {
        module.log(Log.WARN, tag, "resolution_$status class=$className method=$method")
        report("UNAVAILABLE", "${status.lowercase()}:$className.$method")
    }

    fun submitNoise(snapshot: NativeHeadsetSnapshot, mode: String, address: String?, current: () -> NativeHeadsetSnapshot?): Boolean {
        if (!snapshot.supportsNoise(mode)) {
            report("UNSUPPORTED", "mode=$mode is absent from the current capability snapshot")
            return false
        }
        val normalizedAddress = normalizeBluetoothAddress(address)?.takeIf(snapshot.addresses::contains) ?: run {
            report("UNAVAILABLE", "mode=$mode request address is not in the authenticated snapshot")
            return false
        }
        val result = NativeNoiseRequestGate.submit(snapshot, mode, normalizedAddress, callbacks, current)
        reportNoiseRequest(snapshot, mode, result)
        return result != SystemRequestGate.Result.REJECTED
    }

    fun reportNoiseRequest(snapshot: NativeHeadsetSnapshot, mode: String, result: SystemRequestGate.Result) {
        if (result == SystemRequestGate.Result.REJECTED) module.log(Log.WARN, tag, "native noise dispatch rejected mode=$mode")
        val detail = when (result) {
            SystemRequestGate.Result.ACCEPTED -> "mode=$mode session=${snapshot.sessionId}; awaiting device confirmation"
            SystemRequestGate.Result.DUPLICATE -> "mode=$mode session=${snapshot.sessionId}; duplicate vendor callback coalesced"
            SystemRequestGate.Result.REJECTED -> "mode=$mode session=${snapshot.sessionId}; authenticated control callback unavailable"
        }
        report(if (result == SystemRequestGate.Result.REJECTED) "UNAVAILABLE" else "SUBMITTED", detail)
    }

    fun report(status: String, detail: String) {
        hostAdapterCall(onFailure = { module.log(Log.WARN, tag, "diagnostic_callback_failed feature=$feature status=$status", it) }) {
            callbacks.report(feature, status, detail)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        registered.values.forEach { runCatching { it.unhook() } }
        registered.clear()
    }
}

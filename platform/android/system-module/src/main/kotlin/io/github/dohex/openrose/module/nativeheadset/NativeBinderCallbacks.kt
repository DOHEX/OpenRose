package io.github.dohex.openrose.module.nativeheadset

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.dohex.openrose.androidintegration.SystemHeadsetMapping
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.androidintegration.wire.NativeSessionReference
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

internal class NativeBinderCallbacks(
    private val directory: NativeHeadsetDirectory,
    private val hooks: NativeHeadsetHooks,
    private val module: XposedModule,
    private val isClosed: () -> Boolean,
) {
    private val registeredCallbacks = ConcurrentHashMap<IBinder, RegisteredCallback>()
    private val callbackHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastReportedSnapshot: NativeHeadsetSnapshot? = null

    fun publish(fresh: NativeHeadsetSnapshot?) {
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
            hooks.report("UNAVAILABLE", "status payload contains fields the current core snapshot cannot represent")
            return
        }
        if (status.caseOmitted) {
            hooks.report("UNAVAILABLE", "case battery omitted because its charging state is not in the core snapshot")
        }
        targets.forEach { target -> sendRefresh(target, fresh, status.text) }
    }

    fun registerCallback(callback: Any, snapshot: NativeHeadsetSnapshot, address: String): Boolean {
        val binder = callbackBinder(callback) ?: run {
            hooks.report("UNAVAILABLE", "callback binder unavailable")
            return false
        }
        val normalizedAddress = normalizeBluetoothAddress(address) ?: return false
        val death = IBinder.DeathRecipient { registeredCallbacks.remove(binder) }
        runCatching { binder.linkToDeath(death, 0) }.onFailure {
            hooks.report("UNAVAILABLE", "callback binder already dead")
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
            hooks.report("UNAVAILABLE", "initial status callback cannot represent current snapshot")
        } else {
            val registered = registeredCallbacks[binder] ?: return false
            sendRefresh(registered, snapshot, status.text)
            hooks.report("RESOLVED", "native refresh callback registered for ${snapshot.deviceId}")
        }
        return true
    }

    private fun sendRefresh(target: RegisteredCallback, snapshot: NativeHeadsetSnapshot, payload: String) {
        callbackHandler.post {
            if (isClosed() || registeredCallbacks[target.binder] !== target) return@post
            if (directory.session(target.address)?.reference != target.reference) return@post
            hostAdapterCall(onFailure = { failure ->
                registeredCallbacks.remove(target.binder, target)
                unlinkDeath(target)
                module.log(Log.WARN, TAG, "refreshStatus_callback_failed session=${snapshot.sessionId}", failure)
                hooks.report("UNAVAILABLE", "refreshStatus callback failed for current session")
            }) {
                val method = target.callback.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "refreshStatus" && candidate.parameterTypes.contentEquals(
                        arrayOf(String::class.java, String::class.java),
                    )
                } ?: throw NoSuchMethodException("refreshStatus(String,String)")
                method.isAccessible = true
                method.invoke(target.callback, target.address, payload)
            }
        }
    }

    private fun callbackBinder(callback: Any): IBinder? = runCatching {
        val method = resolveMethod(callback.javaClass, "asBinder", emptyArray()) ?: return null
        method.invoke(callback) as? IBinder
    }.onFailure { module.log(Log.WARN, TAG, "callback_asBinder_failed", it) }.getOrNull()

    fun removeCallback(callback: Any) {
        val binder = if (callback is IBinder) callback else callbackBinder(callback) ?: return
        registeredCallbacks.remove(binder)?.let(::unlinkDeath)
    }

    private fun unlinkDeath(callback: RegisteredCallback) {
        runCatching { callback.binder.unlinkToDeath(callback.death, 0) }
    }

    fun close() {
        registeredCallbacks.values.forEach(::unlinkDeath)
        registeredCallbacks.clear()
        lastReportedSnapshot = null
        callbackHandler.removeCallbacksAndMessages(null)
    }

    private data class RegisteredCallback(
        val callback: Any,
        val binder: IBinder,
        val address: String,
        val reference: NativeSessionReference,
        val death: IBinder.DeathRecipient,
    ) {
        fun matches(snapshot: NativeHeadsetSnapshot): Boolean = reference == snapshot.reference && address in snapshot.addresses
    }

    private companion object {
        const val TAG = "OpenRose-HeadsetBinder"
    }
}

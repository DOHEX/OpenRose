package io.github.dohex.openrose.integration

import android.os.SystemClock
import io.github.dohex.openrose.androidintegration.SystemRequestGate
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot

/** Callbacks are supplied by the authenticated reader lease in the target process. */
interface AuthenticatedNativeHeadsetCallbacks {
    /**
     * Enqueue a request against this exact bridge snapshot and return immediately. This must not
     * perform Binder IPC or wait for device confirmation on the native method's caller thread.
     */
    fun submitNoise(snapshot: NativeHeadsetSnapshot, mode: String): Boolean

    /**
     * Report adapter diagnostics through the existing authenticated bridge; detail must be
     * bounded, and this callback must enqueue locally without waiting for Binder IPC.
     */
    fun report(feature: String, status: String, detail: String)

    /**
     * The control device the fusion center's settings entry for [address] should open in OpenRose, decided by
     * the module host from its own state, or null when the system's own entry must run. Waits a bounded time.
     */
    fun fusionEntryDevice(address: String): String?
}

/** Coalesces two synchronous vendor entry points for one UI tap without owning a command queue. */
internal object NativeNoiseRequestGate {
    private val gate = SystemRequestGate({ SystemClock.elapsedRealtime() })

    fun submit(snapshot: NativeHeadsetSnapshot, mode: String, enqueue: () -> Boolean): SystemRequestGate.Result {
        val key = Key(snapshot.hostEpoch, snapshot.deviceId, snapshot.sessionId, mode)
        return gate.submit(key, enqueue)
    }

    private data class Key(val hostEpoch: String, val deviceId: String, val sessionId: Long, val mode: String)
}

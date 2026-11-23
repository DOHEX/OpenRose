package io.github.dohex.openrose.module.nativeheadset

import android.os.SystemClock
import io.github.dohex.openrose.androidintegration.SystemRequestGate
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot

interface AuthenticatedNativeHeadsetCallbacks {
    fun submitNoise(snapshot: NativeHeadsetSnapshot, mode: String): Boolean

    fun report(feature: String, status: String, detail: String)

    fun fusionEntryDevice(address: String): String?
}

internal object NativeNoiseRequestGate {
    private val gate = SystemRequestGate({ SystemClock.elapsedRealtime() })

    fun submit(
        snapshot: NativeHeadsetSnapshot,
        mode: String,
        address: String,
        callbacks: AuthenticatedNativeHeadsetCallbacks,
        currentSnapshot: () -> NativeHeadsetSnapshot?,
    ): SystemRequestGate.Result {
        val key = Key(snapshot.hostEpoch, snapshot.deviceId, snapshot.sessionId, mode)
        return gate.submit(key) {
            val current = currentSnapshot()?.takeIf {
                it.reference == snapshot.reference && it.matchesAddress(address) && it.supportsNoise(mode)
            }
            current != null && callbacks.submitNoise(current, mode)
        }
    }

    private data class Key(val hostEpoch: String, val deviceId: String, val sessionId: Long, val mode: String)
}

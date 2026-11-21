package io.github.dohex.openrose.module.nativeheadset

import android.bluetooth.BluetoothDevice
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetDirectory
import io.github.dohex.openrose.androidintegration.wire.NativeHeadsetSnapshot
import io.github.dohex.openrose.androidintegration.wire.NativeSessionReference
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress
import java.util.Collections
import java.util.WeakHashMap

internal class NativeBinderBindings(private val directory: NativeHeadsetDirectory) {
    private val binderAddress = Collections.synchronizedMap(WeakHashMap<Any, BinderAddress>())

    fun clear() = binderAddress.clear()

    /**
     * Binds a binder instance to the ROSE address it identified. Without a session the binding carries no reference: its
     * address-less calls (`register`) stay off the vendor path until a session for that address exists, which it then
     * admits, as on the target source device of a headset circulation (ADR 0017).
     */
    fun rememberAddress(owner: Any?, address: String?) {
        if (owner == null || !directory.identifies(address)) return
        val normalized = normalizeBluetoothAddress(address) ?: return
        val reference = directory.session(normalized)?.reference
        binderAddress[owner] = BinderAddress(normalized, reference)
    }

    fun snapshotForActionDevice(owner: Any?, value: Any?): NativeHeadsetSnapshot? {
        val device = value as? BluetoothDevice ?: return null
        val address = runCatching { device.address }.getOrNull() ?: return null
        val normalized = normalizeBluetoothAddress(address) ?: return null
        val snapshot = directory.session(normalized) ?: return null
        val binding = binderAddress[owner]
        if (binding != null && binding.address == normalized && !binding.admits(snapshot)) return null
        return snapshot
    }

    data class BoundHeadset(val address: String, val snapshot: NativeHeadsetSnapshot?)

    fun boundHeadset(owner: Any?): BoundHeadset? {
        val binding = binderAddress[owner] ?: return null
        val current = directory.session(binding.address)?.takeIf(binding::admits)
        return BoundHeadset(binding.address, current)
    }

    fun shouldSuppressManagedAction(owner: Any?, value: Any?): Boolean {
        val address = when (value) {
            is BluetoothDevice -> runCatching { value.address }.getOrNull()
            is String -> value
            else -> null
        }
        val normalized = normalizeBluetoothAddress(address) ?: return false
        if (!directory.isManaged(normalized)) return false
        val snapshot = directory.session(normalized)
        if (snapshot == null) return true
        val binding = binderAddress[owner]
        return binding != null && binding.address == normalized && !binding.admits(snapshot)
    }

    private data class BinderAddress(
        val address: String,
        /** Null when bound to bonded earphones with no session. */
        val reference: NativeSessionReference?,
    ) {
        /** A binding made in a session keeps to that session; one made with none admits the session for its address. */
        fun admits(snapshot: NativeHeadsetSnapshot): Boolean = reference == null || reference == snapshot.reference
    }
}

package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

class NativeHeadsetDirectory(private val now: () -> Long) {
    private val lock = Any()
    private var current: NativeHeadsetSnapshot? = null
    private var bonded: Set<String> = emptySet()

    private val managed = linkedSetOf<String>()

    fun publishSession(snapshot: NativeHeadsetSnapshot?): NativeHeadsetSnapshot? = synchronized(lock) {
        val fresh = snapshot?.takeIf { it.isFresh(now()) }
        fresh?.addresses?.mapNotNullTo(managed, ::normalizeBluetoothAddress)
        current = fresh
        fresh
    }

    fun publishBonded(addresses: Set<String>) = synchronized(lock) {
        val normalized = addresses.mapNotNullTo(linkedSetOf(), ::normalizeBluetoothAddress)
        managed += normalized
        bonded = normalized
    }

    fun current(): NativeHeadsetSnapshot? = synchronized(lock) { current?.takeIf { it.isFresh(now()) } }

    fun session(address: String?): NativeHeadsetSnapshot? = current()?.takeIf { it.matchesAddress(address) }

    fun identifies(address: String?): Boolean {
        if (session(address) != null) return true
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return synchronized(lock) { normalized in bonded }
    }

    fun isManaged(address: String?): Boolean {
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return synchronized(lock) { normalized in managed }
    }

    companion object {
        const val BINDER_AUDIO_SWITCH = "1"
        const val BINDER_SUPPORT_MASK = "${NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID},000000000000000010000000"

        const val MILINK_AUDIO_SWITCH = 0
    }
}

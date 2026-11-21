package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

/**
 * What the system entries in one target process may answer for a Bluetooth address (ADR 0017). Identity queries are
 * answered for a fresh current session and for the bonded ROSE earphones the module host publishes; state queries
 * only from the fresh current session, so nothing stale is presented. [now] is the elapsed-realtime clock the host
 * stamps snapshots with.
 */
class NativeHeadsetDirectory(private val now: () -> Long) {
    private val lock = Any()
    private var current: NativeHeadsetSnapshot? = null
    private var bonded: Set<String> = emptySet()

    // Every address either source ever named, kept until close: elapsed time or a withdrawn list never reopens the
    // vendor's own control path for ROSE earphones, and no TTL or eviction can admit a stale action.
    private val managed = linkedSetOf<String>()

    /** Replaces the current session; returns it when fresh, otherwise null and no session is held. */
    fun publishSession(snapshot: NativeHeadsetSnapshot?): NativeHeadsetSnapshot? = synchronized(lock) {
        val fresh = snapshot?.takeIf { it.isFresh(now()) }
        fresh?.addresses?.mapNotNullTo(managed, ::normalizeBluetoothAddress)
        current = fresh
        fresh
    }

    /** Replaces the bonded ROSE earphones, in any address spelling; an empty set (no module owner) identifies nothing. */
    fun publishBonded(addresses: Set<String>) = synchronized(lock) {
        val normalized = addresses.mapNotNullTo(linkedSetOf(), ::normalizeBluetoothAddress)
        managed += normalized
        bonded = normalized
    }

    /** The fresh current session, whatever address it answers to. */
    fun current(): NativeHeadsetSnapshot? = synchronized(lock) { current?.takeIf { it.isFresh(now()) } }

    /** The fresh current session answering to [address]; the only source for state queries. */
    fun session(address: String?): NativeHeadsetSnapshot? = current()?.takeIf { it.matchesAddress(address) }

    /** Whether identity queries for [address] answer as ROSE earphones. */
    fun identifies(address: String?): Boolean {
        if (session(address) != null) return true
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return synchronized(lock) { normalized in bonded }
    }

    /** Whether [address] ever named ROSE earphones in this module generation. */
    fun isManaged(address: String?): Boolean {
        val normalized = normalizeBluetoothAddress(address) ?: return false
        return synchronized(lock) { normalized in managed }
    }

    fun clear() = synchronized(lock) {
        current = null
        bonded = emptySet()
        managed.clear()
    }

    /**
     * The identity answers, fixed values rather than readings. The Xiaomi Bluetooth headset binder's follow the pinned
     * OppoPods reference `c201e25`: `isSupportAudioSwitch` reports support, and `checkSupport` / `getDeviceInfo` report
     * the compatibility device id with its support mask. The mask's bits are undocumented; it is kept whole because
     * HyperOS headset circulation is gated on these answers, and each part is dropped once on-device bisection shows
     * it does nothing.
     */
    companion object {
        const val BINDER_AUDIO_SWITCH = "1"
        const val BINDER_SUPPORT_MASK = "${NativeHeadsetSnapshot.MILINK_COMPATIBILITY_DEVICE_ID},000000000000000010000000"

        /** MiLink's `isSupportAudioSwitch` gates the spatial audio card (OppoPods `b9313e9`), not circulation: it stays off. */
        const val MILINK_AUDIO_SWITCH = 0
    }
}

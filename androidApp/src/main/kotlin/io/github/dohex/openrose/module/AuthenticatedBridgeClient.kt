package io.github.dohex.openrose.module

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Consumer of the module host's single control session, running in a target process.
 *
 * The host lives in `com.android.bluetooth`; this client discovers its endpoint by binding an
 * existing exported service there and is authorised by the host from the Binder caller's uid, so it
 * holds no credential. This class never creates a ControlSession or transport. The snapshot callback
 * is invoked only after the host has authorised this client, confirmed the current owner generation
 * and acknowledged the exact read.
 *
 * Two epochs exist and must not be mixed: the `open` reply's [BridgeWire.HOST_EPOCH] is the host
 * incarnation (diagnostics only), while `currentSession` reports the current owner generation, which
 * every later request carries and every snapshot echoes.
 */
internal class AuthenticatedBridgeClient(
    context: Context,
    private val target: BridgeTarget,
    private val processName: String,
    private val onSnapshot: (Bundle) -> Unit,
    private val onClear: () -> Unit,
    private val onAuthenticated: (AuthenticatedBridgeClient) -> Unit = {},
    private val onClosing: () -> Boolean = { true },
    private val resolution: String = BridgeWire.RESOLUTION_MATCHED,
) {
    private val context = context.applicationContext ?: context
    private val lock = Any()
    private val readerThread = HandlerThread(READER_THREAD_NAME).apply { start() }

    internal val handler: Handler = Handler(readerThread.looper)

    @Volatile private var started = false

    @Volatile private var closing = false

    @Volatile private var closed = false

    @Volatile private var closeResult: Boolean? = null

    /** Host incarnation from `open`; diagnostics only, never sent back. */
    @Volatile private var hostIncarnation: String? = null

    /** The owner generation last confirmed by `currentSession`; the only epoch commands may carry. */
    @Volatile private var generation: String? = null

    @Volatile private var leaseId: String? = null

    @Volatile private var currentReference: SessionReference? = null

    @Volatile private var currentSnapshot: Bundle? = null

    @Volatile private var targetBridge: IControlBridge? = null

    @Volatile private var currentBinding: BridgeBinding? = null
    private var authenticatedLease: String? = null
    private var watchedGeneration: String? = null
    private var deliveredSnapshot = false
    private val rebindRunnable = Runnable { bindControlBridge() }

    init {
        require(processName.isNotBlank() && processName.length <= 128)
        require(target.acceptsProcess(processName)) {
            "Loaded process '$processName' is outside bridge target $target"
        }
        require(resolution in BridgeWire.RESOLUTIONS)
        require(context.packageName == target.packageName) {
            "Bridge context package ${context.packageName} does not match $target"
        }
    }

    /** Binds the host discovery service and starts one serialized reader loop. */
    fun start() {
        synchronized(lock) {
            if (started || closing || closed) return
            started = true
        }
        handler.post { bindControlBridge() }
    }

    private fun bindControlBridge() {
        if (!canRun()) return
        if (currentBinding != null) return

        val binding = BridgeBinding()
        currentBinding = binding
        val intent = Intent(BridgeWire.ACTION_CONTROL_HOST)
            .setComponent(ComponentName(BridgeWire.TARGET_PACKAGE, BridgeWire.HOST_SERVICE_CLASS))
        val bound = try {
            val accepted = context.bindService(intent, binding, Context.BIND_AUTO_CREATE)
            // A connection registered with a failed bind must still be unbound.
            binding.isBound = true
            accepted
        } catch (failure: Throwable) {
            Log.w(TAG, "bridge_bind_failed target=$target", failure)
            false
        }
        if (currentBinding !== binding || closing || closed) {
            unbindQuietly(binding)
            return
        }
        if (!bound) {
            recordStatus("bind_failed")
            currentBinding = null
            clearSnapshot()
            unbindQuietly(binding)
            scheduleRebind()
            return
        }
        schedulePoll(binding)
    }

    private fun scheduleRebind() {
        if (!canRun()) return
        handler.removeCallbacks(rebindRunnable)
        handler.postDelayed(rebindRunnable, REBIND_BACKOFF_MILLIS)
    }

    private fun pollBridge(binding: BridgeBinding) {
        var nextDelay = POLL_INTERVAL_MILLIS
        try {
            if (!isCurrentBinding(binding)) return
            val bridge = binding.bridge ?: return

            if (leaseId == null) {
                val opened = bridge.open(BridgeBundles.hello(processName, target, resolution), binding.clientToken)
                val openedStatus = opened.getString(BridgeWire.STATUS) ?: STATUS_MISSING
                val openedLease = opened.getString(BridgeWire.LEASE_ID)
                if (!hasWireVersion(opened) || openedStatus != BridgeWire.STATUS_OK || openedLease.isNullOrBlank()) {
                    if (!openedLease.isNullOrBlank()) runCatching { bridge.close(openedLease) }
                    clearSnapshot()
                    if (hasWireVersion(opened) && openedStatus == BridgeWire.STATUS_UNAUTHORIZED) {
                        nextDelay = REJECTED_RETRY_MILLIS
                        if (recordStatus("rejected_by_host")) Log.w(TAG, "bridge_open_unauthorized target=$target")
                    } else {
                        recordStatus(if (hasWireVersion(opened)) "open_$openedStatus" else "open_invalid_version")
                    }
                    return
                }

                leaseId = openedLease
                targetBridge = bridge
                hostIncarnation = opened.getString(BridgeWire.HOST_EPOCH)
                generation = null
                watchedGeneration = null
                currentReference = null
                authenticatedLease = openedLease
                recordStatus("authenticated target=$target host=${hostIncarnation ?: "unknown"}")
                runCatching { onAuthenticated(this) }
                    .onFailure { Log.w(TAG, "authenticated_callback_failed target=$target", it) }
            }

            readCurrentSnapshot(binding, bridge)
        } catch (failure: SecurityException) {
            nextDelay = REJECTED_RETRY_MILLIS
            closeLease(binding.bridge)
            if (recordStatus("rejected_by_host")) Log.w(TAG, "bridge_request_rejected target=$target", failure)
        } catch (failure: Throwable) {
            clearSnapshot()
            if (recordStatus("request_failed_${failure.javaClass.simpleName}")) {
                Log.w(TAG, "bridge_request_failed target=$target", failure)
            }
        } finally {
            schedulePoll(binding, nextDelay)
        }
    }

    private fun readCurrentSnapshot(binding: BridgeBinding, bridge: IControlBridge) {
        val lease = leaseId ?: return
        if (!isCurrentBinding(binding)) return

        val current = bridge.currentSession(lease)
        if (!hasWireVersion(current)) {
            unconfirm()
            recordStatus("session_invalid_version")
            return
        }

        val sessionStatus = current.getString(BridgeWire.STATUS) ?: STATUS_MISSING
        if (sessionStatus == BridgeWire.STATUS_NO_SESSION) {
            // No owner or no ready device: revoke consumers, keep the lease for component reports.
            clearSnapshot()
            confirmGeneration(generationOf(current))
            recordStatus("no_session")
            return
        }
        if (sessionStatus != BridgeWire.STATUS_OK) {
            unconfirm()
            if (sessionStatus == BridgeWire.STATUS_STALE_HOST || sessionStatus == BridgeWire.STATUS_STALE_SESSION ||
                sessionStatus == BridgeWire.STATUS_UNAUTHORIZED
            ) {
                closeLease(bridge)
            }
            recordStatus("session_$sessionStatus")
            return
        }

        val ownerGeneration = generationOf(current)
        val deviceId = current.getString(BridgeWire.DEVICE_ID)
        val sessionId = current.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
        if (ownerGeneration == null || deviceId.isNullOrBlank() || deviceId.length > MAX_DEVICE_ID_LENGTH ||
            sessionId <= 0L
        ) {
            unconfirm()
            recordStatus("session_identity_mismatch")
            return
        }
        // A different generation revokes everything delivered for the previous one before any read.
        confirmGeneration(ownerGeneration)

        if (watchedGeneration != ownerGeneration) {
            val watched = bridge.watch(leaseRequest(lease, ownerGeneration), binding.observer)
            val watchStatus = if (hasWireVersion(watched)) watched.getString(BridgeWire.STATUS) ?: STATUS_MISSING else "INVALID_VERSION"
            if (watchStatus != BridgeWire.STATUS_OK) {
                unconfirm()
                if (watchStatus != BridgeWire.STATUS_STALE_HOST && watchStatus != BridgeWire.STATUS_STALE_SESSION) {
                    closeLease(bridge)
                }
                recordStatus("watch_$watchStatus")
                return
            }
            watchedGeneration = ownerGeneration
            if (!isCurrentBinding(binding) || leaseId != lease || generation != ownerGeneration) return
        }

        val response = bridge.read(sessionRequest(lease, ownerGeneration, deviceId, sessionId))
        if (!hasWireVersion(response)) {
            clearSnapshot()
            recordStatus("read_invalid_version")
            return
        }
        val readStatus = response.getString(BridgeWire.STATUS) ?: STATUS_MISSING
        if (readStatus != BridgeWire.STATUS_OK) {
            failRequest(bridge, readStatus)
            recordStatus("read_$readStatus")
            return
        }

        val snapshot = response.getBundle(BridgeWire.SNAPSHOT)
        if (snapshot == null || !hasWireVersion(snapshot) ||
            snapshot.getString(BridgeWire.HOST_EPOCH) != ownerGeneration ||
            snapshot.getString(BridgeWire.DEVICE_ID) != deviceId ||
            snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID) != sessionId
        ) {
            clearSnapshot()
            recordStatus("snapshot_identity_mismatch")
            return
        }
        val sequence = snapshot.getLong(BridgeWire.READ_SEQUENCE, INVALID_READ_SEQUENCE)
        if (sequence <= 0L) {
            clearSnapshot()
            recordStatus("snapshot_read_sequence_missing")
            return
        }

        val receipt = sessionRequest(lease, ownerGeneration, deviceId, sessionId).apply {
            putLong(BridgeWire.READ_SEQUENCE, sequence)
        }
        val acknowledged = bridge.acknowledge(receipt)
        val ackStatus = if (hasWireVersion(acknowledged)) {
            acknowledged.getString(BridgeWire.STATUS) ?: STATUS_MISSING
        } else {
            "INVALID_VERSION"
        }
        if (ackStatus != BridgeWire.STATUS_OK) {
            failRequest(bridge, ackStatus)
            recordStatus("ack_$ackStatus")
            return
        }

        if (!isCurrentBinding(binding) || leaseId != lease || generation != ownerGeneration) return
        currentReference = SessionReference(deviceId, sessionId)
        currentSnapshot = Bundle(snapshot)
        deliveredSnapshot = true
        try {
            onSnapshot(Bundle(snapshot))
            recordStatus("snapshot_acknowledged session=$sessionId")
        } catch (failure: Throwable) {
            Log.e(TAG, "snapshot_callback_failed target=$target", failure)
            clearSnapshot()
        }
    }

    /** A rejected read or acknowledgement revokes consumers; the next poll re-confirms the generation. */
    private fun failRequest(bridge: IControlBridge, status: String) {
        unconfirm()
        if (status == BridgeWire.STATUS_UNAUTHORIZED) closeLease(bridge)
    }

    private fun generationOf(bundle: Bundle): String? = bundle.getString(BridgeWire.HOST_EPOCH)
        ?.takeIf { it.isNotBlank() && it.length <= MAX_GENERATION_LENGTH }

    /**
     * Adopts the owner generation reported by `currentSession`. A change revokes every consumer of
     * the previous generation and invalidates its watch; the new generation is then re-announced so
     * pending component reports reach the new owner.
     */
    private fun confirmGeneration(confirmed: String?) {
        if (generation == confirmed) return
        clearSnapshot()
        generation = confirmed
        watchedGeneration = null
        if (confirmed != null && authenticatedLease != null) {
            runCatching { onAuthenticated(this) }
                .onFailure { Log.w(TAG, "authenticated_callback_failed target=$target", it) }
        }
    }

    /** No generation is confirmed any more: consumers are revoked and commands are refused. */
    private fun unconfirm() {
        clearSnapshot()
        confirmGeneration(null)
    }

    private fun closeLease(bridge: IControlBridge?) {
        val lease = leaseId
        // Clear the target-process presentation before releasing its authenticated lease.
        clearSnapshot()
        invalidateLeaseLocal()
        if (lease != null && bridge != null) {
            runCatching { bridge.close(lease) }
                .onFailure { Log.w(TAG, "bridge_lease_close_failed target=$target", it) }
        }
    }

    /** Clear on the reader looper only; a stale binding can never clear a newer target adapter. */
    private fun clearSnapshot() {
        currentReference = null
        currentSnapshot = null
        clearDeliveredSnapshot()
    }

    private fun clearDeliveredSnapshot() {
        if (!deliveredSnapshot) return
        deliveredSnapshot = false
        runCatching { onClear() }
            .onFailure { Log.w(TAG, "bridge_clear_callback_failed target=$target", it) }
    }

    private fun schedulePoll(binding: BridgeBinding, delayMillis: Long = 0L) {
        if (!isCurrentBinding(binding)) return
        handler.removeCallbacks(binding.pollRunnable)
        handler.postDelayed(binding.pollRunnable, delayMillis)
    }

    private fun isCurrentBinding(binding: BridgeBinding): Boolean = started && !closing && !closed && currentBinding === binding

    private fun canRun(): Boolean = started && !closing && !closed

    private fun recordStatus(status: String): Boolean {
        val binding = currentBinding
        if (binding == null || binding.lastStatus == status) return false
        binding.lastStatus = status
        Log.i(TAG, "bridge_status target=$target status=$status")
        return true
    }

    private fun hasWireVersion(bundle: Bundle): Boolean = bundle.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) == BridgeWire.VERSION

    private fun leaseRequest(lease: String, ownerGeneration: String?): Bundle = Bundle().apply {
        putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
        putString(BridgeWire.LEASE_ID, lease)
        ownerGeneration?.let { putString(BridgeWire.HOST_EPOCH, it) }
    }

    private fun sessionRequest(
        lease: String,
        ownerGeneration: String,
        deviceId: String,
        sessionId: Long,
    ): Bundle = leaseRequest(lease, ownerGeneration).apply {
        putString(BridgeWire.DEVICE_ID, deviceId)
        putLong(BridgeWire.SESSION_ID, sessionId)
    }

    /**
     * A command may only address the snapshot it was built from, and only while that snapshot's
     * generation is still the one `currentSession` last confirmed.
     */
    private fun snapshotRequest(snapshot: Bundle, requireCurrentReference: Boolean): Bundle? {
        if (!isLeaseUsable()) return null
        val confirmed = generation ?: return null
        val lease = leaseId ?: return null
        val snapshotGeneration = snapshot.getString(BridgeWire.HOST_EPOCH) ?: return null
        val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf {
            it.isNotBlank() && it.length <= MAX_DEVICE_ID_LENGTH
        } ?: return null
        val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
            .takeIf { it > 0L } ?: return null
        if (snapshot.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) != BridgeWire.VERSION ||
            snapshotGeneration != confirmed || (requireCurrentReference && currentReference != SessionReference(deviceId, sessionId))
        ) {
            return null
        }
        return sessionRequest(lease, confirmed, deviceId, sessionId)
    }

    private fun isLeaseUsable(): Boolean = started && !closed && !closing && leaseId != null && targetBridge != null

    /** Atomically claims the current connection prompt for this authorised session. */
    fun claimPrompt(snapshot: Bundle, promptId: String): Boolean {
        if (promptId.isBlank() || promptId.length > MAX_PROMPT_ID_LENGTH) return false
        val request = snapshotRequest(snapshot, requireCurrentReference = true) ?: return false
        request.putString(BridgePresentationWire.PROMPT_ID, promptId)
        val response = callRemote(request) { bridge, call -> bridge.claimPrompt(call) } ?: return false
        return hasWireVersion(response) && response.getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK
    }

    /** The caller must invoke this synchronous write from its separate bounded action executor. */
    fun setNoise(snapshot: Bundle, mode: String): Bundle {
        if (mode.isBlank() || mode.length > MAX_NOISE_MODE_LENGTH) return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val request = snapshotRequest(snapshot, requireCurrentReference = true)
            ?: return BridgeBundles.response(BridgeWire.STATUS_STALE_SESSION)
        request.putString(BridgePresentationWire.NOISE_MODE, mode)
        val response = callRemote(request) { bridge, call -> bridge.setNoise(call) }
            ?: return BridgeBundles.response(BridgeWire.STATUS_STALE_SESSION)
        if (hasWireVersion(response) && response.getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK) {
            currentBinding?.let { schedulePoll(it) }
        }
        return response
    }

    fun reportPresentation(snapshot: Bundle, feature: String, status: String, detail: String) {
        if (feature !in BridgePresentationWire.FEATURES || status !in BridgePresentationWire.STATUSES) return
        val request = snapshotRequest(snapshot, requireCurrentReference = true) ?: return
        request.putString(BridgePresentationWire.FEATURE, feature)
        request.putString(BridgeWire.STATUS, status)
        request.putString(BridgePresentationWire.DETAIL, detail.take(MAX_DETAIL_LENGTH))
        callRemote(request) { bridge, call -> bridge.reportPresentation(call) }
    }

    /**
     * Component capability reports are lease-scoped and remain available with no ready session; they
     * carry the owner generation only while one is confirmed.
     */
    fun reportComponent(feature: String, status: String, detail: String) {
        if (feature.isBlank() || feature.length > MAX_FEATURE_LENGTH ||
            status.isBlank() || status.length > MAX_STATUS_LENGTH
        ) {
            return
        }
        val request = currentLeaseRequest() ?: return
        request.putString(BridgePresentationWire.FEATURE, feature)
        request.putString(BridgeWire.STATUS, status)
        request.putString(BridgePresentationWire.DETAIL, detail.take(MAX_DETAIL_LENGTH))
        callRemote(request) { bridge, call -> bridge.reportComponent(call) }
    }

    /**
     * Asks the host whether the fusion center's settings entry for [address] belongs to OpenRose. Returns the
     * control device to open, or null when the system's own entry must run (no owner, system chosen, unknown
     * address, or any failure). A blocking Binder call: use it from the bounded action executor.
     */
    fun fusionEntryDevice(address: String): String? {
        val request = currentLeaseRequest() ?: return null
        request.putString(BridgeWire.FUSION_ADDRESS, address)
        val response = callRemote(request) { bridge, call -> bridge.fusionEntry(call) } ?: return null
        if (!hasWireVersion(response) || response.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK ||
            response.getString(BridgeWire.FUSION_DECISION) != BridgeWire.FUSION_OPENROSE
        ) {
            return null
        }
        return response.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isNotBlank() && it.length <= MAX_DEVICE_ID_LENGTH }
    }

    private fun currentLeaseRequest(): Bundle? {
        if (!isLeaseUsable()) return null
        val lease = leaseId ?: return null
        if (authenticatedLease != lease) return null
        return leaseRequest(lease, generation)
    }

    private fun callRemote(
        request: Bundle,
        call: (IControlBridge, Bundle) -> Bundle,
    ): Bundle? {
        if (!isLeaseUsable()) return null
        val lease = leaseId ?: return null
        // The generation may have moved since the request was built; never send an old one.
        if (request.getString(BridgeWire.LEASE_ID) != lease || request.getString(BridgeWire.HOST_EPOCH) != generation) return null
        val bridge = targetBridge ?: return null
        return try {
            call(bridge, Bundle(request))
        } catch (failure: SecurityException) {
            // A refusal by the host is an answer, not a missing one: callers must be able to tell it from a lost link.
            Log.w(TAG, "bridge_call_unauthorized target=$target", failure)
            BridgeBundles.response(BridgeWire.STATUS_UNAUTHORIZED, failure.message)
        } catch (failure: Throwable) {
            Log.w(TAG, "bridge_call_failed target=$target", failure)
            null
        }
    }

    fun close(): Boolean {
        synchronized(lock) {
            closeResult?.let { return it }
            if (closing) return false
            closing = true
        }

        val completed = CountDownLatch(1)
        val cleanupSucceeded = booleanArrayOf(false)
        val cleanup = Runnable {
            try {
                cleanupSucceeded[0] = cleanupOnReaderThread()
            } catch (failure: Throwable) {
                Log.e(TAG, "bridge_close_failed target=$target", failure)
                cleanupSucceeded[0] = false
            } finally {
                closed = true
                readerThread.quitSafely()
                completed.countDown()
            }
        }

        if (Looper.myLooper() == handler.looper) {
            cleanup.run()
            // The reader cannot join itself, so report that shutdown was not fully confirmed.
            closeResult = false
            return false
        }

        val posted = handler.post(cleanup)
        if (!posted) {
            readerThread.quitSafely()
            closeResult = false
            return false
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MILLIS)
        val finished = try {
            completed.await(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            closeResult = false
            return false
        }

        val remainingMillis = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0L))
        try {
            if (remainingMillis > 0L) readerThread.join(remainingMillis)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            closeResult = false
            return false
        }
        val result = cleanupSucceeded[0] && !readerThread.isAlive
        closeResult = result
        return result
    }

    private fun cleanupOnReaderThread(): Boolean {
        var clean = true
        handler.removeCallbacks(rebindRunnable)
        currentBinding?.pollRunnable?.let(handler::removeCallbacks)
        clearSnapshot()
        if (!runCatching(onClosing).onFailure {
                clean = false
                Log.e(TAG, "bridge_feature_cleanup_failed target=$target", it)
            }.getOrDefault(false)
        ) {
            clean = false
        }

        val binding = currentBinding
        closeLease(binding?.bridge ?: targetBridge)
        if (binding != null) {
            currentBinding = null
            unbindQuietly(binding)
        }
        started = false
        return clean
    }

    /**
     * The host connection was lost: consumers are revoked at once and a fresh bind is scheduled with a
     * fixed backoff for as long as this client is open, because the host may start (or restart) later.
     */
    private fun dropBinding(binding: BridgeBinding, status: String) {
        if (currentBinding !== binding || closing || closed) return
        recordStatus(status)
        currentBinding = null
        binding.bridge = null
        handler.removeCallbacks(binding.pollRunnable)
        clearSnapshot()
        invalidateLeaseLocal()
        unbindQuietly(binding)
        scheduleRebind()
    }

    private fun invalidateLeaseLocal() {
        leaseId = null
        authenticatedLease = null
        targetBridge = null
        hostIncarnation = null
        generation = null
        watchedGeneration = null
        currentReference = null
        currentSnapshot = null
    }

    private fun unbindQuietly(binding: BridgeBinding) {
        if (!binding.isBound) return
        binding.isBound = false
        try {
            context.unbindService(binding)
        } catch (failure: IllegalArgumentException) {
            Log.w(TAG, "bridge_unbind_failed reason=not_registered target=$target", failure)
        } catch (failure: Throwable) {
            Log.e(TAG, "bridge_unbind_failed target=$target", failure)
        }
    }

    private inner class BridgeBinding : ServiceConnection {
        val clientToken = Binder()
        val pollRunnable = Runnable { pollBridge(this) }
        val observer = object : IControlObserver.Stub() {
            override fun onChanged(identity: Bundle) {
                // The generation in the notice is not trusted; any valid notice for this lease only
                // prompts a re-read, which re-confirms the generation through currentSession.
                if (identity.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) != BridgeWire.VERSION ||
                    identity.getString(BridgeWire.LEASE_ID) != leaseId
                ) {
                    return
                }
                schedulePoll(this@BridgeBinding)
            }
        }

        @Volatile var bridge: IControlBridge? = null

        @Volatile var isBound: Boolean = false
        var lastStatus: String? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val endpoint = IControlBridge.Stub.asInterface(service)
            handler.post {
                if (!canRun() || currentBinding !== this) return@post
                bridge = endpoint
                targetBridge = endpoint
                recordStatus("bridge_service_connected component=${name.flattenToShortString()}")
                schedulePoll(this)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bridge = null
            if (currentBinding === this) targetBridge = null
            handler.post { dropBinding(this, "service_disconnected") }
        }

        override fun onBindingDied(name: ComponentName) {
            bridge = null
            if (currentBinding === this) targetBridge = null
            handler.post { dropBinding(this, "binding_died") }
        }

        override fun onNullBinding(name: ComponentName) {
            bridge = null
            if (currentBinding === this) targetBridge = null
            handler.post { dropBinding(this, "null_binding") }
        }
    }

    private data class SessionReference(val deviceId: String, val sessionId: Long)

    private companion object {
        const val TAG = "OpenRoseBridgeClient"
        const val READER_THREAD_NAME = "OpenRoseAuthenticatedBridge"
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val REBIND_BACKOFF_MILLIS = 5_000L
        const val REJECTED_RETRY_MILLIS = 5_000L
        const val CLOSE_TIMEOUT_MILLIS = 3_000L
        const val MAX_GENERATION_LENGTH = 64
        const val MAX_DEVICE_ID_LENGTH = 128
        const val MAX_PROMPT_ID_LENGTH = 128
        const val MAX_NOISE_MODE_LENGTH = 64
        const val MAX_FEATURE_LENGTH = 64
        const val MAX_STATUS_LENGTH = 64
        const val MAX_DETAIL_LENGTH = 512
        const val STATUS_MISSING = "MISSING_STATUS"
        const val INVALID_WIRE_VERSION = -1
        const val INVALID_READ_SEQUENCE = -1L
        const val INVALID_SESSION_ID = Long.MIN_VALUE
    }
}

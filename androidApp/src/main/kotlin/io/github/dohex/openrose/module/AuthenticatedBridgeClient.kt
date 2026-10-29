package io.github.dohex.openrose.module

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.dohex.openrose.bridge.BridgePresentationWire
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.BridgeWire
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlObserver
import io.github.libxposed.api.XposedModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Authenticated reader for an application-owned control session in a target process.
 *
 * This class never creates a ControlSession or transport. The snapshot callback is invoked only
 * after the target has validated its identity and the host has acknowledged the exact read.
 */
internal class AuthenticatedBridgeClient(
    private val module: XposedModule,
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
    @Volatile private var hostEpoch: String? = null
    @Volatile private var proofKey: String? = null
    @Volatile private var leaseId: String? = null
    @Volatile private var currentReference: SessionReference? = null
    @Volatile private var currentSnapshot: Bundle? = null
    @Volatile private var lastRejectedCredential: String? = null
    @Volatile private var targetBridge: IControlBridge? = null

    @Volatile private var currentBinding: BridgeBinding? = null
    private var remotePreferences: SharedPreferences? = null
    private var preferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var authenticatedLease: String? = null
    private var deliveredSnapshot = false

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

    /** Binds the canonical explicit AIDL service and starts one serialized reader loop. */
    fun start() {
        synchronized(lock) {
            if (started || closing || closed) return
            started = true
        }
        handler.post { bindControlBridge(attempt = 0) }
    }

    private fun bindControlBridge(attempt: Int) {
        if (!canRun()) return
        if (currentBinding != null) return

        val binding = BridgeBinding(attempt)
        currentBinding = binding
        val intent = Intent().setComponent(ComponentName(BridgeWire.APP_PACKAGE, BridgeWire.SERVICE_CLASS))
        val bound = try {
            context.bindService(intent, binding, Context.BIND_AUTO_CREATE)
        } catch (failure: Throwable) {
            Log.w(TAG, "bridge_bind_failed target=$target", failure)
            false
        }
        binding.isBound = bound
        if (currentBinding !== binding || closing || closed) {
            if (bound) unbindQuietly(binding)
            return
        }
        if (!bound) {
            currentBinding = null
            clearSnapshot()
            if (attempt < MAX_BINDING_RETRIES) {
                handler.postDelayed({ bindControlBridge(attempt + 1) }, BIND_RETRY_MILLIS)
            } else {
                Log.w(TAG, "bridge_bind_unavailable target=$target")
            }
            return
        }
        schedulePoll(binding)
    }

    private fun pollBridge(binding: BridgeBinding) {
        try {
            if (!isCurrentBinding(binding)) return
            val bridge = binding.bridge ?: return
            val preferences = module.getRemotePreferences(BridgeWire.PREFERENCES_GROUP)
            attachPreferencesListener(binding, preferences)

            val epoch = preferences.getString(BridgeWire.HOST_EPOCH, null)
            val key = preferences.getString(BridgeWire.PROOF_KEY, null)
            if (epoch.isNullOrBlank() || key.isNullOrBlank()) {
                forgetCredentials(binding, bridge)
                recordStatus("waiting_for_host_credentials")
                return
            }
            if (epoch.length > MAX_EPOCH_LENGTH || key.length != PROOF_KEY_LENGTH || !key.all { it.isHexDigit() }) {
                forgetCredentials(binding, bridge)
                recordStatus("invalid_host_credentials")
                return
            }

            if (hostEpoch != epoch || proofKey != key) {
                closeLease(binding, bridge)
                hostEpoch = epoch
                proofKey = key
                lastRejectedCredential = null
            }

            if (leaseId == null) {
                val fingerprint = "$epoch:$key"
                if (lastRejectedCredential == fingerprint) return
                val proof = BridgeWire.proof(
                    keyHex = key,
                    hostEpoch = epoch,
                    processName = processName,
                    uid = Process.myUid(),
                    pid = Process.myPid(),
                    resolution = resolution,
                    target = target,
                )
                val opened = bridge.open(proof, binding.clientToken)
                val openedStatus = opened.getString(BridgeWire.STATUS) ?: STATUS_MISSING
                val openedLease = opened.getString(BridgeWire.LEASE_ID)
                val openedEpoch = opened.getString(BridgeWire.HOST_EPOCH)
                if (!hasWireVersion(opened) || openedStatus != BridgeWire.STATUS_OK ||
                    openedLease.isNullOrBlank() || openedEpoch != epoch
                ) {
                    if (!openedLease.isNullOrBlank()) runCatching { bridge.close(openedLease) }
                    clearSnapshot()
                    if (openedStatus == BridgeWire.STATUS_STALE_HOST || openedEpoch != epoch ||
                        openedStatus == BridgeWire.STATUS_TARGET_INCOMPATIBLE
                    ) lastRejectedCredential = fingerprint
                    recordStatus(if (hasWireVersion(opened)) "open_$openedStatus" else "open_invalid_version")
                    return
                }

                leaseId = openedLease
                targetBridge = bridge
                currentReference = null
                authenticatedLease = null
                val watched = bridge.watch(leaseRequest(openedLease, epoch), binding.observer)
                if (!hasWireVersion(watched) || watched.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
                    closeLease(binding, bridge)
                    recordStatus("watch_unavailable")
                    return
                }
                if (!isCurrentBinding(binding) || leaseId != openedLease || hostEpoch != epoch) return
                authenticatedLease = openedLease
                recordStatus("authenticated target=$target")
                runCatching { onAuthenticated(this) }
                    .onFailure { Log.w(TAG, "authenticated_callback_failed target=$target", it) }
            }

            readCurrentSnapshot(binding, bridge, epoch)
        } catch (failure: SecurityException) {
            val epoch = hostEpoch
            val key = proofKey
            if (epoch != null && key != null) lastRejectedCredential = "$epoch:$key"
            closeLease(binding, binding.bridge)
            if (recordStatus("rejected_by_host")) Log.e(TAG, "bridge_request_rejected target=$target", failure)
        } catch (failure: Throwable) {
            clearSnapshot()
            if (recordStatus("request_failed_${failure.javaClass.simpleName}")) {
                Log.w(TAG, "bridge_request_failed target=$target", failure)
            }
        } finally {
            schedulePoll(binding, POLL_INTERVAL_MILLIS)
        }
    }

    private fun readCurrentSnapshot(binding: BridgeBinding, bridge: IControlBridge, epoch: String) {
        val lease = leaseId ?: return
        if (!isCurrentBinding(binding) || hostEpoch != epoch) return

        val current = bridge.currentSession(lease)
        if (!hasWireVersion(current)) {
            clearSnapshot()
            recordStatus("session_invalid_version")
            return
        }

        val sessionStatus = current.getString(BridgeWire.STATUS) ?: STATUS_MISSING
        if (sessionStatus == BridgeWire.STATUS_NO_SESSION) {
            clearSnapshot()
            recordStatus("no_session")
            return // The authenticated lease remains active for component reports.
        }
        if (sessionStatus != BridgeWire.STATUS_OK) {
            clearSnapshot()
            if (sessionStatus == BridgeWire.STATUS_STALE_HOST || sessionStatus == BridgeWire.STATUS_STALE_SESSION) {
                closeLease(binding, bridge)
            }
            recordStatus("session_$sessionStatus")
            return
        }

        val currentEpoch = current.getString(BridgeWire.HOST_EPOCH)
        val deviceId = current.getString(BridgeWire.DEVICE_ID)
        val sessionId = current.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
        if (currentEpoch != epoch || deviceId.isNullOrBlank() || deviceId.length > MAX_DEVICE_ID_LENGTH ||
            sessionId <= 0L
        ) {
            clearSnapshot()
            recordStatus("session_identity_mismatch")
            return
        }

        val request = sessionRequest(lease, epoch, deviceId, sessionId)
        val response = bridge.read(request)
        if (!hasWireVersion(response)) {
            clearSnapshot()
            recordStatus("read_invalid_version")
            return
        }
        val readStatus = response.getString(BridgeWire.STATUS) ?: STATUS_MISSING
        if (readStatus != BridgeWire.STATUS_OK) {
            clearSnapshot()
            if (readStatus == BridgeWire.STATUS_STALE_HOST || readStatus == BridgeWire.STATUS_STALE_SESSION) {
                closeLease(binding, bridge)
            }
            recordStatus("read_$readStatus")
            return
        }

        val snapshot = response.getBundle(BridgeWire.SNAPSHOT)
        if (snapshot == null || !hasWireVersion(snapshot) ||
            snapshot.getString(BridgeWire.HOST_EPOCH) != epoch ||
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

        val receipt = sessionRequest(lease, epoch, deviceId, sessionId).apply {
            putLong(BridgeWire.READ_SEQUENCE, sequence)
        }
        val acknowledged = bridge.acknowledge(receipt)
        if (!hasWireVersion(acknowledged) || acknowledged.getString(BridgeWire.STATUS) != BridgeWire.STATUS_OK) {
            clearSnapshot()
            val ackStatus = if (hasWireVersion(acknowledged)) {
                acknowledged.getString(BridgeWire.STATUS) ?: STATUS_MISSING
            } else {
                "INVALID_VERSION"
            }
            if (ackStatus == BridgeWire.STATUS_STALE_HOST || ackStatus == BridgeWire.STATUS_STALE_SESSION) {
                closeLease(binding, bridge)
            }
            recordStatus("ack_$ackStatus")
            return
        }

        if (!isCurrentBinding(binding) || leaseId != lease || hostEpoch != epoch) return
        val acknowledgedReference = SessionReference(deviceId, sessionId)
        currentReference = acknowledgedReference
        currentSnapshot = Bundle(snapshot)
        deliveredSnapshot = true
        if (!isCurrentBinding(binding)) return
        try {
            onSnapshot(Bundle(snapshot))
            recordStatus("snapshot_acknowledged session=$sessionId")
        } catch (failure: Throwable) {
            Log.e(TAG, "snapshot_callback_failed target=$target", failure)
            clearSnapshot()
        }
    }

    private fun attachPreferencesListener(binding: BridgeBinding, preferences: SharedPreferences) {
        if (binding.preferences === preferences) return
        detachPreferencesListener(binding)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == BridgeWire.HOST_EPOCH || key == BridgeWire.PROOF_KEY) {
                schedulePoll(binding)
            }
        }
        binding.preferences = preferences
        binding.preferencesListener = listener
        remotePreferences = preferences
        preferencesListener = listener
        runCatching { preferences.registerOnSharedPreferenceChangeListener(listener) }
            .onFailure { Log.w(TAG, "remote_preferences_listener_unavailable target=$target", it) }
    }

    private fun forgetCredentials(binding: BridgeBinding, bridge: IControlBridge?) {
        closeLease(binding, bridge)
        hostEpoch = null
        proofKey = null
        lastRejectedCredential = null
    }

    private fun closeLease(binding: BridgeBinding?, bridge: IControlBridge?) {
        val lease = leaseId
        // Clear the target-process presentation before releasing its authenticated lease.
        clearDeliveredSnapshot()
        leaseId = null
        authenticatedLease = null
        targetBridge = null
        currentReference = null
        currentSnapshot = null
        if (lease != null && bridge != null) {
            runCatching { bridge.close(lease) }
                .onFailure { Log.w(TAG, "bridge_lease_close_failed target=$target", it) }
        }
        if (binding != null && !isCurrentBinding(binding)) return
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

    private fun isCurrentBinding(binding: BridgeBinding): Boolean =
        started && !closing && !closed && currentBinding === binding

    private fun canRun(): Boolean = started && !closing && !closed

    private fun recordStatus(status: String): Boolean {
        val binding = currentBinding
        if (binding == null || binding.lastStatus == status) return false
        binding.lastStatus = status
        Log.i(TAG, "bridge_status target=$target status=$status")
        return true
    }

    private fun hasWireVersion(bundle: Bundle): Boolean =
        bundle.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) == BridgeWire.VERSION

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun leaseRequest(lease: String, epoch: String): Bundle = Bundle().apply {
        putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
        putString(BridgeWire.LEASE_ID, lease)
        putString(BridgeWire.HOST_EPOCH, epoch)
    }

    private fun sessionRequest(lease: String, epoch: String, deviceId: String, sessionId: Long): Bundle =
        leaseRequest(lease, epoch).apply {
            putString(BridgeWire.DEVICE_ID, deviceId)
            putLong(BridgeWire.SESSION_ID, sessionId)
        }

    private fun snapshotRequest(snapshot: Bundle, requireCurrentReference: Boolean): Bundle? {
        if (!isLeaseUsable()) return null
        val epoch = hostEpoch ?: return null
        val lease = leaseId ?: return null
        val snapshotEpoch = snapshot.getString(BridgeWire.HOST_EPOCH) ?: return null
        val deviceId = snapshot.getString(BridgeWire.DEVICE_ID)?.takeIf {
            it.isNotBlank() && it.length <= MAX_DEVICE_ID_LENGTH
        } ?: return null
        val sessionId = snapshot.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
            .takeIf { it > 0L } ?: return null
        if (snapshot.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) != BridgeWire.VERSION ||
            snapshotEpoch != epoch || (requireCurrentReference && currentReference != SessionReference(deviceId, sessionId))
        ) return null
        return sessionRequest(lease, epoch, deviceId, sessionId)
    }

    private fun isLeaseUsable(): Boolean =
        started && !closed && !closing && leaseId != null && targetBridge != null

    /** Atomically claims the current connection prompt for this authenticated session. */
    fun claimPrompt(snapshot: Bundle, promptId: String): Boolean {
        if (promptId.isBlank() || promptId.length > MAX_PROMPT_ID_LENGTH) return false
        val request = snapshotRequest(snapshot, requireCurrentReference = true) ?: return false
        request.putString(BridgePresentationWire.PROMPT_ID, promptId)
        val response = callRemote(request) { bridge, call -> bridge.claimPrompt(call) } ?: return false
        return hasWireVersion(response) && response.getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK
    }

    /** The caller must invoke this synchronous write from its separate bounded action executor. */
    fun setNoise(snapshot: Bundle, mode: String): Bundle {
        if (mode.isBlank() || mode.length > MAX_NOISE_MODE_LENGTH) return BridgeWire.response(BridgeWire.STATUS_INVALID_ARGUMENT)
        val request = snapshotRequest(snapshot, requireCurrentReference = true)
            ?: return BridgeWire.response(BridgeWire.STATUS_STALE_SESSION)
        request.putString(BridgePresentationWire.NOISE_MODE, mode)
        val response = callRemote(request) { bridge, call -> bridge.setNoise(call) }
            ?: return BridgeWire.response(BridgeWire.STATUS_STALE_SESSION)
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

    /** Component capability reports are lease-scoped and remain available with no ready session. */
    fun reportComponent(feature: String, status: String, detail: String) {
        if (feature.isBlank() || feature.length > MAX_FEATURE_LENGTH ||
            status.isBlank() || status.length > MAX_STATUS_LENGTH
        ) return
        val request = currentLeaseRequest() ?: return
        request.putString(BridgePresentationWire.FEATURE, feature)
        request.putString(BridgeWire.STATUS, status)
        request.putString(BridgePresentationWire.DETAIL, detail.take(MAX_DETAIL_LENGTH))
        callRemote(request) { bridge, call -> bridge.reportComponent(call) }
    }

    private fun currentLeaseRequest(): Bundle? {
        if (!isLeaseUsable()) return null
        val lease = leaseId ?: return null
        val epoch = hostEpoch ?: return null
        if (authenticatedLease != lease) return null
        return leaseRequest(lease, epoch)
    }

    private fun callRemote(
        request: Bundle,
        call: (IControlBridge, Bundle) -> Bundle,
    ): Bundle? {
        if (!isLeaseUsable()) return null
        val lease = leaseId ?: return null
        val epoch = hostEpoch ?: return null
        if (request.getString(BridgeWire.LEASE_ID) != lease || request.getString(BridgeWire.HOST_EPOCH) != epoch) return null
        val bridge = targetBridge ?: return null
        return runCatching { call(bridge, Bundle(request)) }
            .onFailure { Log.w(TAG, "bridge_call_failed target=$target", it) }
            .getOrNull()
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
        currentBinding?.pollRunnable?.let(handler::removeCallbacks)
        clearSnapshot()
        if (!runCatching(onClosing).onFailure {
                clean = false
                Log.e(TAG, "bridge_feature_cleanup_failed target=$target", it)
            }.getOrDefault(false)
        ) clean = false

        val binding = currentBinding
        val bridge = binding?.bridge ?: targetBridge
        closeLease(binding, bridge)
        detachPreferencesListener(binding)
        if (binding != null) {
            currentBinding = null
            unbindQuietly(binding)
        }
        hostEpoch = null
        proofKey = null
        lastRejectedCredential = null
        started = false
        return clean
    }

    private fun handleServiceDisconnected(binding: BridgeBinding) {
        if (currentBinding !== binding || closing || closed) return
        binding.bridge = null
        targetBridge = null
        invalidateLeaseLocal()
        detachPreferencesListener(binding)
        clearSnapshot()
        recordStatus("service_disconnected")
    }

    private fun handleBindingDied(binding: BridgeBinding) {
        if (currentBinding !== binding || closing || closed) return
        val retry = binding.attempt < MAX_BINDING_RETRIES
        currentBinding = null
        binding.bridge = null
        invalidateLeaseLocal()
        detachPreferencesListener(binding)
        clearSnapshot()
        unbindQuietly(binding)
        if (retry) bindControlBridge(binding.attempt + 1)
        else recordStatus("binding_died")
    }

    private fun handleNullBinding(binding: BridgeBinding) {
        if (currentBinding !== binding || closing || closed) return
        currentBinding = null
        binding.bridge = null
        invalidateLeaseLocal()
        detachPreferencesListener(binding)
        clearSnapshot()
        unbindQuietly(binding)
        recordStatus("null_binding")
    }

    private fun invalidateLeaseLocal() {
        leaseId = null
        authenticatedLease = null
        targetBridge = null
        currentReference = null
        currentSnapshot = null
    }

    private fun detachPreferencesListener(binding: BridgeBinding?) {
        val preferences = binding?.preferences ?: remotePreferences
        val listener = binding?.preferencesListener ?: preferencesListener
        if (preferences != null && listener != null) {
            runCatching { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
                .onFailure { Log.w(TAG, "remote_preferences_listener_remove_failed target=$target", it) }
        }
        if (binding != null) {
            binding.preferences = null
            binding.preferencesListener = null
        }
        if (remotePreferences === preferences) remotePreferences = null
        if (preferencesListener === listener) preferencesListener = null
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

    private inner class BridgeBinding(val attempt: Int) : ServiceConnection {
        val clientToken = Binder()
        val pollRunnable = Runnable { pollBridge(this) }
        val observer = object : IControlObserver.Stub() {
            override fun onChanged(identity: Bundle) {
                if (identity.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) != BridgeWire.VERSION ||
                    identity.getString(BridgeWire.HOST_EPOCH) != hostEpoch ||
                    identity.getString(BridgeWire.LEASE_ID) != leaseId
                ) return
                schedulePoll(this@BridgeBinding)
            }
        }

        @Volatile var bridge: IControlBridge? = null
        @Volatile var isBound: Boolean = false
        var preferences: SharedPreferences? = null
        var preferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
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
            handler.post { handleServiceDisconnected(this) }
        }

        override fun onBindingDied(name: ComponentName) {
            bridge = null
            if (currentBinding === this) targetBridge = null
            handler.post { handleBindingDied(this) }
        }

        override fun onNullBinding(name: ComponentName) {
            bridge = null
            if (currentBinding === this) targetBridge = null
            handler.post { handleNullBinding(this) }
        }
    }

    private data class SessionReference(val deviceId: String, val sessionId: Long)

    private companion object {
        const val TAG = "OpenRoseBridgeClient"
        const val READER_THREAD_NAME = "OpenRoseAuthenticatedBridge"
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val BIND_RETRY_MILLIS = 1_000L
        const val CLOSE_TIMEOUT_MILLIS = 3_000L
        const val MAX_BINDING_RETRIES = 1
        const val MAX_EPOCH_LENGTH = 64
        const val PROOF_KEY_LENGTH = 64
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

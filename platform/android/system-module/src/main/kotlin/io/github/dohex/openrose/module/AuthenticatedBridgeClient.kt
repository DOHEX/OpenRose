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
import android.os.RemoteException
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.androidintegration.wire.BridgeWire
import io.github.dohex.openrose.bridge.BridgeBundles
import io.github.dohex.openrose.bridge.BridgeTarget
import io.github.dohex.openrose.bridge.IControlBridge
import io.github.dohex.openrose.bridge.IControlObserver
import java.util.concurrent.CancellationException

internal class AuthenticatedBridgeClient(
    context: Context,
    private val target: BridgeTarget,
    private val processName: String,
    private val onSnapshot: (Bundle) -> Unit,
    private val onSnapshotFresh: (Long) -> Unit,
    private val onClear: () -> Unit,
    private val onBonded: (Set<String>) -> Unit,
    private val onAuthenticated: (AuthenticatedBridgeClient) -> Unit = {},
    private val onClosing: () -> Unit = {},
    private val resolution: String = BridgeWire.RESOLUTION_MATCHED,
) {
    private val context = context.applicationContext ?: context
    private val lock = Any()
    private val readerThread = HandlerThread(READER_THREAD_NAME).apply { start() }

    internal val handler: Handler = Handler(readerThread.looper)

    @Volatile private var started = false

    @Volatile private var closing = false

    @Volatile private var closed = false

    @Volatile private var hostIncarnation: String? = null

    @Volatile private var generation: String? = null

    @Volatile private var leaseId: String? = null

    @Volatile private var currentReference: SessionReference? = null

    private var deliveredSequence = INVALID_READ_SEQUENCE

    @Volatile private var targetBridge: IControlBridge? = null

    @Volatile private var currentBinding: BridgeBinding? = null
    private var authenticatedLease: String? = null
    private var watchedGeneration: String? = null
    private var deliveredSnapshot = false
    private var deliveredBonded: Set<String> = emptySet()
    private val rebindRunnable = Runnable { bindControlBridge() }

    init {
        require(processName.isNotBlank() && processName.length <= 128)
    }
    private val observations = SnapshotReader()
    private val confirmation = ConfirmedLease()
    val calls = SystemCalls()

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
        val bound = binderReply { context.bindService(intent, binding, Context.BIND_AUTO_CREATE) }
            .onSuccess { binding.isBound = true }
            .getOrElse { failure ->
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
            confirmation.clearSnapshot()
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

    private fun schedulePoll(binding: BridgeBinding, delayMillis: Long = 0L) {
        if (!isCurrentBinding(binding)) return
        handler.removeCallbacks(binding.pollRunnable)
        handler.postDelayed(binding.pollRunnable, delayMillis)
    }

    private fun isCurrentBinding(binding: BridgeBinding): Boolean = canRun() && currentBinding === binding

    private fun canRun(): Boolean = started && !closing && !closed

    private fun recordStatus(status: String): Boolean {
        val binding = currentBinding
        if (binding == null || binding.lastStatus == status) return false
        binding.lastStatus = status
        Log.i(TAG, "bridge_status target=$target status=$status")
        return true
    }

    fun close(timeoutMillis: Long = CLOSE_TIMEOUT_MILLIS): Boolean {
        synchronized(lock) {
            if (!closing && !closed) {
                closing = true
                if (!handler.post(::finishClose)) closing = false
            }
        }
        if (Looper.myLooper() != handler.looper) {
            try {
                readerThread.join(timeoutMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return closed && !readerThread.isAlive
    }

    private fun finishClose() {
        runCatching {
            handler.removeCallbacks(rebindRunnable)
            val binding = currentBinding
            binding?.pollRunnable?.let(handler::removeCallbacks)
            confirmation.clearSnapshot()
            onClosing()
            confirmation.closeLease(binding?.bridge ?: targetBridge)
            if (binding != null) {
                currentBinding = null
                unbindQuietly(binding)
            }
            started = false
            closed = true
            readerThread.quitSafely()
        }.onFailure { failure ->
            Log.e(TAG, "bridge_close_failed target=$target", failure)
            synchronized(lock) { closing = false }
        }
    }

    private fun dropBinding(binding: BridgeBinding, status: String) {
        if (currentBinding !== binding || closing || closed) return
        recordStatus(status)
        currentBinding = null
        binding.bridge = null
        handler.removeCallbacks(binding.pollRunnable)
        confirmation.clearSnapshot()
        confirmation.invalidateLeaseLocal()
        unbindQuietly(binding)
        scheduleRebind()
    }

    private fun unbindQuietly(binding: BridgeBinding) {
        if (!binding.isBound) return
        binding.isBound = false
        binderReply { context.unbindService(binding) }.onFailure { failure ->
            if (failure is IllegalArgumentException) {
                Log.w(TAG, "bridge_unbind_failed reason=not_registered target=$target", failure)
            } else {
                Log.e(TAG, "bridge_unbind_failed target=$target", failure)
            }
        }
    }

    private inner class BridgeBinding : ServiceConnection {
        val clientToken = Binder()
        val pollRunnable = Runnable { observations.pollBridge(this) }
        val observer = object : IControlObserver.Stub() {
            override fun onChanged(identity: Bundle) {
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

    private inner class SnapshotReader {
        fun pollBridge(binding: BridgeBinding) {
            var nextDelay = POLL_INTERVAL_MILLIS
            try {
                if (!isCurrentBinding(binding)) return
                val bridge = binding.bridge ?: return

                binderReply {
                    if (leaseId == null) nextDelay = openLease(binding, bridge)
                    if (leaseId != null) readCurrentSnapshot(binding, bridge)
                }.onFailure { failure ->
                    if (failure is SecurityException) {
                        nextDelay = REJECTED_RETRY_MILLIS
                        confirmation.closeLease(bridge)
                        if (recordStatus("rejected_by_host")) Log.w(TAG, "bridge_request_rejected target=$target", failure)
                    } else {
                        confirmation.clearSnapshot()
                        confirmation.publishBonded(emptySet())
                        if (recordStatus("request_failed_${failure.javaClass.simpleName}")) {
                            Log.w(TAG, "bridge_request_failed target=$target", failure)
                        }
                    }
                }
            } finally {
                schedulePoll(binding, nextDelay)
            }
        }

        private fun openLease(binding: BridgeBinding, bridge: IControlBridge): Long {
            val opened = bridge.open(BridgeBundles.hello(processName, target, resolution), binding.clientToken)
            val status = opened.getString(BridgeWire.STATUS) ?: STATUS_MISSING
            val lease = opened.getString(BridgeWire.LEASE_ID)
            val validVersion = hasWireVersion(opened)
            if (!validVersion || status != BridgeWire.STATUS_OK || lease.isNullOrBlank()) {
                if (!lease.isNullOrBlank()) runCatching { bridge.close(lease) }
                confirmation.clearSnapshot()
                if (validVersion && status == BridgeWire.STATUS_UNAUTHORIZED) {
                    if (recordStatus("rejected_by_host")) Log.w(TAG, "bridge_open_unauthorized target=$target")
                    return REJECTED_RETRY_MILLIS
                }
                recordStatus(if (validVersion) "open_$status" else "open_invalid_version")
            } else {
                leaseId = lease
                targetBridge = bridge
                hostIncarnation = opened.getString(BridgeWire.HOST_EPOCH)
                generation = null
                watchedGeneration = null
                currentReference = null
                authenticatedLease = lease
                recordStatus("authenticated target=$target host=${hostIncarnation ?: "unknown"}")
                confirmation.notifyAuthenticated()
            }
            return POLL_INTERVAL_MILLIS
        }

        private fun readCurrentSnapshot(binding: BridgeBinding, bridge: IControlBridge) {
            val lease = leaseId ?: return
            if (!isCurrentBinding(binding)) return

            val current = bridge.currentSession(lease)
            if (!sessionReady(bridge, current)) return
            val ownerGeneration = generationOf(current)
            val reference = sessionReferenceOf(current)
            if (ownerGeneration == null || reference == null) {
                confirmation.unconfirm()
                recordStatus("session_identity_mismatch")
                return
            }
            confirmation.confirmGeneration(ownerGeneration)
            if (watchGeneration(binding, bridge, lease, ownerGeneration)) {
                val alreadyDelivered = currentReference == reference && deliveredSequence > 0L &&
                    current.getLong(BridgeWire.READ_SEQUENCE, INVALID_READ_SEQUENCE) == deliveredSequence
                if (alreadyDelivered) {
                    systemIntegrationResult {
                        onSnapshotFresh(current.getLong(BridgeWire.CAPTURED_ELAPSED_MILLIS))
                    }.onFailure { failure ->
                        Log.e(TAG, "snapshot_callback_failed target=$target", failure)
                        confirmation.clearSnapshot()
                    }
                } else {
                    readAndAcknowledge(binding, bridge, lease, ownerGeneration, reference)
                }
            }
        }

        private fun sessionReady(bridge: IControlBridge, current: Bundle): Boolean {
            if (!hasWireVersion(current)) {
                confirmation.publishBonded(emptySet())
                confirmation.unconfirm()
                recordStatus("session_invalid_version")
                return false
            }
            confirmation.publishBonded(bondedOf(current))
            return when (val status = current.getString(BridgeWire.STATUS) ?: STATUS_MISSING) {
                BridgeWire.STATUS_OK -> true

                BridgeWire.STATUS_NO_SESSION -> {
                    confirmation.clearSnapshot()
                    confirmation.confirmGeneration(generationOf(current))
                    recordStatus("no_session")
                    false
                }

                else -> {
                    confirmation.unconfirm()
                    if (status == BridgeWire.STATUS_STALE_HOST || status == BridgeWire.STATUS_STALE_SESSION ||
                        status == BridgeWire.STATUS_UNAUTHORIZED
                    ) {
                        confirmation.closeLease(bridge)
                    }
                    recordStatus("session_$status")
                    false
                }
            }
        }

        private fun watchGeneration(binding: BridgeBinding, bridge: IControlBridge, lease: String, ownerGeneration: String): Boolean {
            if (watchedGeneration == ownerGeneration) return true
            val watched = bridge.watch(leaseRequest(lease, ownerGeneration), binding.observer)
            val status = replyStatus(watched)
            if (status != BridgeWire.STATUS_OK) {
                confirmation.unconfirm()
                if (status != BridgeWire.STATUS_STALE_HOST && status != BridgeWire.STATUS_STALE_SESSION) confirmation.closeLease(bridge)
                recordStatus("watch_$status")
                return false
            }
            watchedGeneration = ownerGeneration
            return isCurrentSession(binding, lease, ownerGeneration)
        }

        private fun readAndAcknowledge(
            binding: BridgeBinding,
            bridge: IControlBridge,
            lease: String,
            ownerGeneration: String,
            reference: SessionReference,
        ) {
            val response = bridge.read(sessionRequest(lease, ownerGeneration, reference))
            val snapshot = snapshotFromRead(bridge, response, ownerGeneration, reference) ?: return
            val sequence = snapshot.getLong(BridgeWire.READ_SEQUENCE, INVALID_READ_SEQUENCE)
            if (sequence <= 0L) {
                confirmation.clearSnapshot()
                recordStatus("snapshot_read_sequence_missing")
                return
            }
            val receipt = sessionRequest(lease, ownerGeneration, reference).apply {
                putLong(BridgeWire.READ_SEQUENCE, sequence)
            }
            val ackStatus = replyStatus(bridge.acknowledge(receipt))
            if (ackStatus != BridgeWire.STATUS_OK) {
                failRequest(bridge, ackStatus)
                recordStatus("ack_$ackStatus")
            } else if (isCurrentSession(binding, lease, ownerGeneration)) {
                currentReference = reference
                deliveredSequence = sequence
                deliveredSnapshot = true
                systemIntegrationResult {
                    onSnapshot(Bundle(snapshot))
                    recordStatus("snapshot_acknowledged session=${reference.sessionId}")
                }.onFailure { failure ->
                    Log.e(TAG, "snapshot_callback_failed target=$target", failure)
                    confirmation.clearSnapshot()
                }
            }
        }

        private fun snapshotFromRead(
            bridge: IControlBridge,
            response: Bundle,
            ownerGeneration: String,
            reference: SessionReference,
        ): Bundle? {
            if (!hasWireVersion(response)) {
                confirmation.clearSnapshot()
                recordStatus("read_invalid_version")
                return null
            }
            val status = response.getString(BridgeWire.STATUS) ?: STATUS_MISSING
            return if (status != BridgeWire.STATUS_OK) {
                failRequest(bridge, status)
                recordStatus("read_$status")
                null
            } else {
                val snapshot = response.getBundle(BridgeWire.SNAPSHOT)?.takeIf {
                    hasWireVersion(it) && it.getString(BridgeWire.HOST_EPOCH) == ownerGeneration && sessionReferenceOf(it) == reference
                }
                if (snapshot == null) {
                    confirmation.clearSnapshot()
                    recordStatus("snapshot_identity_mismatch")
                }
                snapshot
            }
        }

        private fun isCurrentSession(
            binding: BridgeBinding,
            lease: String,
            ownerGeneration: String,
        ): Boolean = isCurrentBinding(binding) && leaseId == lease && generation == ownerGeneration

        private fun failRequest(bridge: IControlBridge, status: String) {
            confirmation.unconfirm()
            if (status == BridgeWire.STATUS_UNAUTHORIZED) confirmation.closeLease(bridge)
        }
    }

    private inner class ConfirmedLease {
        fun confirmGeneration(confirmed: String?) {
            if (generation == confirmed) return
            clearSnapshot()
            generation = confirmed
            watchedGeneration = null
            if (confirmed != null && authenticatedLease != null) {
                notifyAuthenticated()
            }
        }

        fun notifyAuthenticated() {
            systemIntegrationResult { onAuthenticated(this@AuthenticatedBridgeClient) }
                .onFailure { Log.w(TAG, "authenticated_callback_failed target=$target", it) }
        }

        fun unconfirm() {
            clearSnapshot()
            confirmGeneration(null)
        }

        fun closeLease(bridge: IControlBridge?) {
            val lease = leaseId
            clearSnapshot()
            invalidateLeaseLocal()
            if (lease != null && bridge != null) {
                runCatching { bridge.close(lease) }
                    .onFailure { Log.w(TAG, "bridge_lease_close_failed target=$target", it) }
            }
        }

        fun clearSnapshot() {
            currentReference = null
            deliveredSequence = INVALID_READ_SEQUENCE
            clearDeliveredSnapshot()
        }

        private fun clearDeliveredSnapshot() {
            if (!deliveredSnapshot) return
            deliveredSnapshot = false
            systemIntegrationResult { onClear() }
                .onFailure { Log.w(TAG, "bridge_clear_callback_failed target=$target", it) }
        }

        fun publishBonded(addresses: Set<String>) {
            if (addresses == deliveredBonded) return
            deliveredBonded = addresses
            systemIntegrationResult { onBonded(addresses) }
                .onFailure { Log.w(TAG, "bridge_bonded_callback_failed target=$target", it) }
        }

        fun invalidateLeaseLocal() {
            leaseId = null
            authenticatedLease = null
            targetBridge = null
            hostIncarnation = null
            generation = null
            watchedGeneration = null
            currentReference = null
            deliveredSequence = INVALID_READ_SEQUENCE
            publishBonded(emptySet())
        }
    }

    inner class SystemCalls {
        private fun snapshotRequest(snapshot: Bundle): Bundle? {
            if (!isLeaseUsable()) return null
            val confirmed = generation ?: return null
            val lease = leaseId ?: return null
            val snapshotGeneration = snapshot.getString(BridgeWire.HOST_EPOCH) ?: return null
            val reference = sessionReferenceOf(snapshot) ?: return null
            if (!hasWireVersion(snapshot) || snapshotGeneration != confirmed || currentReference != reference) return null
            return sessionRequest(lease, confirmed, reference)
        }

        private fun isLeaseUsable(): Boolean = started && !closed && !closing && leaseId != null && targetBridge != null

        fun claimPrompt(snapshot: Bundle, promptId: String): Boolean {
            if (!promptId.isWireText(MAX_PROMPT_ID_LENGTH)) return false
            val request = snapshotRequest(snapshot) ?: return false
            request.putString(BridgePresentationWire.PROMPT_ID, promptId)
            val response = callRemote(request) { bridge, call -> bridge.claimPrompt(call) } ?: return false
            return hasWireVersion(response) && response.getString(BridgeWire.STATUS) == BridgeWire.STATUS_OK
        }

        fun setNoise(snapshot: Bundle, mode: String): Bundle {
            if (!mode.isWireText(MAX_NOISE_MODE_LENGTH)) return BridgeBundles.response(BridgeWire.STATUS_INVALID_ARGUMENT)
            val request = snapshotRequest(snapshot)
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
            val request = snapshotRequest(snapshot) ?: return
            request.putString(BridgePresentationWire.FEATURE, feature)
            request.putString(BridgeWire.STATUS, status)
            request.putString(BridgePresentationWire.DETAIL, detail.take(MAX_DETAIL_LENGTH))
            callRemote(request) { bridge, call -> bridge.reportPresentation(call) }
        }

        fun reportComponent(feature: String, status: String, detail: String) {
            if (!feature.isWireText(MAX_FEATURE_LENGTH) || !status.isWireText(MAX_STATUS_LENGTH)) return
            val request = currentLeaseRequest() ?: return
            request.putString(BridgePresentationWire.FEATURE, feature)
            request.putString(BridgeWire.STATUS, status)
            request.putString(BridgePresentationWire.DETAIL, detail.take(MAX_DETAIL_LENGTH))
            callRemote(request) { bridge, call -> bridge.reportComponent(call) }
        }

        fun fusionEntryDevice(address: String): String? {
            val request = currentLeaseRequest() ?: return null
            request.putString(BridgeWire.FUSION_ADDRESS, address)
            val response = callRemote(request) { bridge, call -> bridge.fusionEntry(call) }
            return response?.takeIf {
                replyStatus(it) == BridgeWire.STATUS_OK && it.getString(BridgeWire.FUSION_DECISION) == BridgeWire.FUSION_OPENROSE
            }?.let(::deviceIdOf)
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
            if (request.getString(BridgeWire.LEASE_ID) != lease || request.getString(BridgeWire.HOST_EPOCH) != generation) return null
            val bridge = targetBridge ?: return null
            return binderReply { call(bridge, Bundle(request)) }.getOrElse { failure ->
                if (failure is SecurityException) {
                    Log.w(TAG, "bridge_call_unauthorized target=$target", failure)
                    BridgeBundles.response(BridgeWire.STATUS_UNAUTHORIZED, failure.message)
                } else {
                    Log.w(TAG, "bridge_call_failed target=$target", failure)
                    null
                }
            }
        }
    }
}

private fun replyStatus(reply: Bundle): String = if (hasWireVersion(reply)) {
    reply.getString(BridgeWire.STATUS) ?: STATUS_MISSING
} else {
    "INVALID_VERSION"
}

private fun generationOf(bundle: Bundle): String? = bundle.getString(BridgeWire.HOST_EPOCH)
    ?.takeIf { it.isWireText(MAX_GENERATION_LENGTH) }

private fun bondedOf(reply: Bundle): Set<String> = reply.getStringArrayList(BridgeWire.BONDED_ADDRESSES).orEmpty()
    .take(MAX_BONDED_ADDRESSES)
    .toSet()

private fun hasWireVersion(bundle: Bundle): Boolean = bundle.getInt(BridgeWire.KEY_VERSION, INVALID_WIRE_VERSION) == BridgeWire.VERSION

private fun leaseRequest(lease: String, ownerGeneration: String?): Bundle = Bundle().apply {
    putInt(BridgeWire.KEY_VERSION, BridgeWire.VERSION)
    putString(BridgeWire.LEASE_ID, lease)
    ownerGeneration?.let { putString(BridgeWire.HOST_EPOCH, it) }
}

private fun sessionRequest(
    lease: String,
    ownerGeneration: String,
    reference: SessionReference,
): Bundle = leaseRequest(lease, ownerGeneration).apply {
    putString(BridgeWire.DEVICE_ID, reference.deviceId)
    putLong(BridgeWire.SESSION_ID, reference.sessionId)
}

private fun sessionReferenceOf(bundle: Bundle): SessionReference? {
    val deviceId = deviceIdOf(bundle) ?: return null
    val sessionId = bundle.getLong(BridgeWire.SESSION_ID, INVALID_SESSION_ID)
    return if (sessionId > 0L) SessionReference(deviceId, sessionId) else null
}

private fun deviceIdOf(bundle: Bundle): String? = bundle.getString(BridgeWire.DEVICE_ID)?.takeIf { it.isWireText(MAX_DEVICE_ID_LENGTH) }

private fun String.isWireText(maxLength: Int): Boolean = isNotBlank() && length <= maxLength

private data class SessionReference(val deviceId: String, val sessionId: Long)

private const val TAG = "OpenRoseBridgeClient"
private const val READER_THREAD_NAME = "OpenRoseAuthenticatedBridge"
private const val POLL_INTERVAL_MILLIS = 1_000L
private const val REBIND_BACKOFF_MILLIS = 5_000L
private const val REJECTED_RETRY_MILLIS = 5_000L
private const val CLOSE_TIMEOUT_MILLIS = 3_000L
private const val MAX_GENERATION_LENGTH = 64
private const val MAX_DEVICE_ID_LENGTH = 128
private const val MAX_PROMPT_ID_LENGTH = 128
private const val MAX_NOISE_MODE_LENGTH = 64
private const val MAX_FEATURE_LENGTH = 64
private const val MAX_STATUS_LENGTH = 64
private const val MAX_DETAIL_LENGTH = 512
private const val STATUS_MISSING = "MISSING_STATUS"
private const val INVALID_WIRE_VERSION = -1
private const val INVALID_READ_SEQUENCE = -1L
private const val INVALID_SESSION_ID = Long.MIN_VALUE
private const val MAX_BONDED_ADDRESSES = 32

@Suppress("TooGenericExceptionCaught")
private inline fun <T> binderReply(action: () -> T): Result<T> = try {
    Result.success(action())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (lost: RemoteException) {
    Result.failure(lost)
} catch (refused: RuntimeException) {
    Result.failure(refused)
}

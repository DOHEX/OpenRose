package io.github.dohex.openrose.module.presentation

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import java.util.UUID

/**
 * Constructs the official HyperOS connection-toast payload inside com.xiaomi.bluetooth, where its
 * FileProvider can serve media to SystemUI, and calls that process's StatusBarManager directly.
 * Prompt claiming and local session identity reject stale OpenRose work; external strong-toast
 * replacement is not observed. CALLED records a system call, not a displayed island.
 */
internal class OfficialConnectionPresenter(
    context: Context,
    private val handler: Handler,
    private val callbacks: PresentationCallbacks,
) : AutoCloseable {
    private val targetContext = context.applicationContext ?: context
    private var active: ActiveToast? = null
    private val media = OfficialPromptMedia(targetContext, handler)

    @Volatile private var closed = false

    /** Prepares media, then atomically claims the current prompt immediately before submission. */
    fun show(snapshot: Bundle, promptId: String) {
        handler.dispatch {
            if (!closed) showOnHandler(Bundle(snapshot), promptId)
        }
    }

    /** Withdraws only the prompt/session currently held by this presenter. */
    fun withdraw(snapshot: Bundle?, promptId: String? = null, reason: String = "prompt_cancelled") {
        val copy = snapshot?.let(::Bundle)
        handler.dispatch {
            if (!closed) withdrawOnHandler(copy, promptId, reason)
        }
    }

    fun clear(snapshot: Bundle? = null) = withdraw(snapshot, reason = "presenter_clear")

    override fun close() {
        if (Looper.myLooper() === handler.looper) {
            closeOnHandler()
        } else if (!handler.post { closeOnHandler() }) {
            // The reader looper has already stopped. Release only this presenter's unique files
            // and grants rather than leaving delayed cleanup behind on a dead looper.
            closed = true
            media.close()
        }
    }

    private fun closeOnHandler() {
        if (closed) {
            media.close()
            return
        }
        closed = true
        try {
            withdrawOnHandler(snapshot = null, promptId = null, reason = "presenter_close")
        } finally {
            media.close()
        }
    }

    private fun showOnHandler(snapshot: Bundle, promptId: String) {
        val request = decodeRequest(snapshot, promptId)
        when {
            request == null -> report(snapshot, BridgePresentationWire.UNAVAILABLE, "official_prompt_or_session_not_ready")

            targetContext.packageName != TARGET_PACKAGE ->
                report(snapshot, BridgePresentationWire.UNAVAILABLE, "official_target_context_must_be_com_xiaomi_bluetooth")

            active?.request?.key != request.key -> {
                active?.let { withdrawActive(it, "superseded_by_new_openrose_prompt", report = false) }
                submitPrompt(snapshot, request)
            }
        }
    }

    private fun submitPrompt(snapshot: Bundle, request: PromptRequest) {
        val token = UUID.randomUUID().toString()
        val clips = media.prepare(request, token).getOrElse { failure ->
            report(
                snapshot,
                BridgePresentationWire.UNAVAILABLE,
                "official_clip_or_provider_probe_failed:${failure.javaClass.simpleName}",
            )
            return
        }

        val payload = buildShowBundle(clips, request)
        if (!callbacks.claimPrompt(snapshot, request.promptId)) {
            media.revoke(clips)
            return
        }
        val failure = runCatching { targetContext.setOfficialStatus(payload) }.exceptionOrNull()
        if (failure == null) {
            active = activateToast(request, clips, snapshot)
            report(
                snapshot,
                BridgePresentationWire.CALLED,
                "xiaomi_bluetooth_set_status_returned;provider_and_uri_grant_probed;systemui_display_unverified",
            )
        } else {
            media.revokeAfter(clips, OFFICIAL_DURATION_MILLIS + URI_RELEASE_GRACE_MILLIS)
            report(
                snapshot,
                BridgePresentationWire.UNAVAILABLE,
                "official_set_status_failed:${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}",
            )
        }
    }

    private fun activateToast(
        request: PromptRequest,
        clips: List<PreparedClip>,
        snapshot: Bundle,
    ): ActiveToast {
        val record = ActiveToast(request, SystemClock.elapsedRealtime() + OFFICIAL_DURATION_MILLIS, clips, Bundle(snapshot))
        record.timeout = Runnable {
            if (active === record) {
                // SystemUI owns the display duration. Expiry only releases this prompt's media.
                active = null
                media.revokeAfter(record.clips, URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            }
        }
        handler.postDelayed(record.timeout!!, OFFICIAL_DURATION_MILLIS)
        return record
    }

    private fun withdrawOnHandler(snapshot: Bundle?, promptId: String?, reason: String) {
        val record = active ?: return
        if (snapshot != null && record.request.identity != identityOf(snapshot)) return
        if (promptId != null && record.request.promptId != promptId) return
        withdrawActive(record, reason, report = true)
    }

    private fun withdrawActive(record: ActiveToast, reason: String, report: Boolean) {
        if (active !== record) return
        active = null
        record.timeout?.let(handler::removeCallbacks)

        val left = record.expiresAtElapsed - SystemClock.elapsedRealtime()
        if (left <= 0L) {
            media.revokeAfter(record.clips, URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            return
        }

        val payload = officialHideBundle()
        // Match OppoPods' immediate hide behavior. Only local OpenRose ownership is known here.
        val failure = runCatching { targetContext.setOfficialStatus(payload) }.exceptionOrNull()
        if (failure == null) {
            media.revokeAfter(record.clips, URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            if (report) {
                report(
                    record.snapshot,
                    BridgePresentationWire.WITHDRAWN,
                    "xiaomi_bluetooth_hide_set_status_returned;systemui_withdrawal_unverified:$reason",
                )
            }
        } else {
            media.revokeAfter(record.clips, left + URI_RELEASE_GRACE_MILLIS, preserveReadGrace = true)
            if (report) {
                val detail = failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName
                report(
                    record.snapshot,
                    BridgePresentationWire.ACTION_FAILED,
                    "official_hide_failed:$detail:$reason",
                )
            }
        }
    }

    private fun report(snapshot: Bundle, status: String, detail: String) {
        runCatching {
            callbacks.report(Bundle(snapshot), BridgePresentationWire.OFFICIAL_CONNECTION, status, detail)
        }.onFailure { Log.w(TAG, "official_connection_report_failed status=$status", it) }
    }

    private data class ActiveToast(
        val request: PromptRequest,
        val expiresAtElapsed: Long,
        val clips: List<PreparedClip>,
        val snapshot: Bundle,
        var timeout: Runnable? = null,
    )
}

private fun Handler.dispatch(block: () -> Unit) {
    if (Looper.myLooper() === looper) block() else post(block)
}

private const val TAG = "OpenRoseOfficialToast"

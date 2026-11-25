package io.github.dohex.openrose.module.presentation

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.module.systemIntegrationResult
import java.security.MessageDigest

internal class ModuleConnectionPrompt(
    private val handler: Handler,
    private val callbacks: PresentationCallbacks,
    private val notifications: PresentationNotifications,
    private val content: PresentationNotificationContent,
    private val currentIdentity: () -> SessionIdentity?,
    private val reportOnce: (Bundle, String, String, String) -> Unit,
) {
    private var activePrompt: ActivePrompt? = null
    private val processedPrompts = mutableSetOf<String>()
    private var promptAwaitingStatus = false

    fun awaitsStatus(snapshot: RenderedSnapshot): Boolean = promptAwaitingStatus && snapshot.statusIsland &&
        activePrompt?.let { it.tag == null && it.identity == snapshot.identity } == true

    fun markStatusShown() {
        promptAwaitingStatus = false
    }

    fun clear() {
        cancelActivePrompt("renderer_clear")
        processedPrompts.clear()
    }

    fun render(snapshot: RenderedSnapshot, canMerge: Boolean) {
        val prompt = modulePrompt(snapshot)
        val promptId = prompt?.getString(BridgePresentationWire.PROMPT_ID)?.takeIf { it.isNotBlank() && it.length <= 128 }
        val pending = activePrompt
        if (pending != null && (pending.identity != snapshot.identity || pending.id != promptId)) {
            cancelActivePrompt("prompt_or_mode_changed")
        }
        if (prompt == null || promptId == null) return

        val merged = activePrompt?.takeIf { it.tag == null }
        if (merged != null && !canMerge) {
            val remaining = merged.deadline - SystemClock.uptimeMillis()
            cancelActivePrompt("status_notification_withdrawn")
            if (remaining > 0) publishModulePrompt(snapshot, merged.id, prompt, canMerge = false, remainingMillis = remaining)
        }

        val key = promptKey(snapshot.identity, promptId)
        if (key in processedPrompts) return
        if (prompt.getBoolean(BridgePresentationWire.CLAIMED, false)) {
            processedPrompts += key
        } else if (claimModulePrompt(snapshot, promptId)) {
            publishModulePrompt(snapshot, promptId, prompt, canMerge)
        }
    }

    private fun modulePrompt(snapshot: RenderedSnapshot): Bundle? {
        if (snapshot.mode != BridgePresentationWire.OPENROSE) return null
        if (!snapshot.presentation.getBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS, false)) return null
        return snapshot.presentation.getBundle(BridgePresentationWire.PROMPT)?.takeIf {
            it.getString(BridgePresentationWire.MODE) == BridgePresentationWire.OPENROSE
        }
    }

    private fun claimModulePrompt(snapshot: RenderedSnapshot, promptId: String): Boolean = runCatching {
        callbacks.claimPrompt(Bundle(snapshot.wire), promptId)
    }.onFailure {
        Log.w(PRESENTATION_TAG, "prompt_claim_failed", it)
        reportOnce(
            snapshot.wire,
            BridgePresentationWire.OPENROSE_CONNECTION,
            BridgePresentationWire.UNAVAILABLE,
            "prompt_claim_temporarily_unavailable",
        )
    }.getOrDefault(false)

    private fun publishModulePrompt(
        snapshot: RenderedSnapshot,
        promptId: String,
        prompt: Bundle,
        canMerge: Boolean,
        remainingMillis: Long? = null,
    ) {
        val key = promptKey(snapshot.identity, promptId)
        if (!notifications.hasNotificationPermission()) {
            processedPrompts += key
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.OPENROSE_CONNECTION,
                BridgePresentationWire.UNAVAILABLE,
                "target_process_notification_permission_missing",
            )
            return
        }
        val duration = (remainingMillis ?: prompt.getLong(BridgePresentationWire.DURATION_MILLIS, DEFAULT_PROMPT_DURATION_MILLIS))
            .coerceIn(1L, MAX_PROMPT_DURATION_MILLIS)
        if (canMerge) {
            promptAwaitingStatus = true
            processedPrompts += key
            startPrompt(ActivePrompt(snapshot.identity, promptId, null, snapshot.wire), duration)
            return
        }
        val tag = "$MODULE_TAG_PREFIX${stableDigest(key)}"
        systemIntegrationResult {
            val extras = content.promptExtras(snapshot, duration)
            notifications.ensureChannel(PROMPT_CHANNEL_ID, "OpenRose 系统提示")
            notifications.post(tag, MODULE_PROMPT_NOTIFICATION_ID, content.promptNotification(snapshot, extras))
            processedPrompts += key
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.OPENROSE_CONNECTION,
                BridgePresentationWire.CALLED,
                "notification_manager_notify_returned",
            )

            startPrompt(ActivePrompt(snapshot.identity, promptId, tag, snapshot.wire), duration)
        }.onFailure { failure ->
            Log.w(PRESENTATION_TAG, "module_prompt_failed", failure)
            runCatching { notifications.cancel(tag, MODULE_PROMPT_NOTIFICATION_ID) }
            processedPrompts += key
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.OPENROSE_CONNECTION,
                BridgePresentationWire.UNAVAILABLE,
                "module_template_or_publish_failed:${failure.javaClass.simpleName}",
            )
        }
    }

    private fun startPrompt(record: ActivePrompt, durationMillis: Long) {
        record.deadline = SystemClock.uptimeMillis() + durationMillis
        activePrompt = record
        record.timeout = Runnable {
            if (activePrompt === record && currentIdentity() == record.identity) {
                cancelActivePrompt("prompt_timeout")
            }
        }
        handler.postDelayed(record.timeout!!, durationMillis)
    }

    private fun cancelActivePrompt(reason: String) {
        val active = activePrompt ?: return
        activePrompt = null
        active.timeout?.let(handler::removeCallbacks)
        val tag = active.tag
        if (tag == null) {
            promptAwaitingStatus = false
            reportOnce(
                active.snapshot,
                BridgePresentationWire.OPENROSE_CONNECTION,
                BridgePresentationWire.WITHDRAWN,
                "merged_module_prompt_ended:$reason",
            )
            return
        }
        if (notifications.available) {
            runCatching { notifications.cancel(tag, MODULE_PROMPT_NOTIFICATION_ID) }
                .onSuccess {
                    reportOnce(
                        active.snapshot,
                        BridgePresentationWire.OPENROSE_CONNECTION,
                        BridgePresentationWire.WITHDRAWN,
                        "owned_module_prompt_cancel_returned:$reason",
                    )
                }
                .onFailure { failure ->
                    reportOnce(
                        active.snapshot,
                        BridgePresentationWire.OPENROSE_CONNECTION,
                        BridgePresentationWire.ACTION_FAILED,
                        "module_prompt_cancel:${failure.javaClass.simpleName}",
                    )
                }
        }
    }

    private fun promptKey(identity: SessionIdentity, promptId: String): String = "${identity.stableKey}:$promptId"

    private data class ActivePrompt(
        val identity: SessionIdentity,
        val id: String,
        val tag: String?,
        val snapshot: Bundle,
        var timeout: Runnable? = null,
        var deadline: Long = 0L,
    )

    private companion object {
        const val MODULE_TAG_PREFIX = "openrose:module:"
        const val MODULE_PROMPT_NOTIFICATION_ID = 10086
        const val DEFAULT_PROMPT_DURATION_MILLIS = 5_000L
        const val MAX_PROMPT_DURATION_MILLIS = 5_000L
    }
}

internal fun stableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(12)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

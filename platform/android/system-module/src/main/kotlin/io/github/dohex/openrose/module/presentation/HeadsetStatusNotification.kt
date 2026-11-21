package io.github.dohex.openrose.module.presentation

import android.os.Bundle
import android.util.Log
import io.github.dohex.openrose.androidintegration.wire.BridgePresentationWire
import io.github.dohex.openrose.module.systemIntegrationResult

/** Owns the persistent headset status notification and its last published content. */
internal class HeadsetStatusNotification(
    private val notifications: PresentationNotifications,
    private val content: PresentationNotificationContent,
    private val reportOnce: (Bundle, String, String, String) -> Unit,
) {
    private var statusTag: String? = null
    private var statusFingerprint: String? = null
    var active = false
        private set
    private var statusIslandPosted = false

    fun render(snapshot: RenderedSnapshot, onPromptShown: (() -> Unit)?) {
        val requestedTag = prepareStatusTag(snapshot) ?: return
        // A merged module prompt is shown as the status island's entrance, replayed by posting the notification afresh.
        systemIntegrationResult { publishStatusCard(snapshot, requestedTag, onPromptShown) }.onFailure { failure ->
            Log.w(PRESENTATION_TAG, "status_notification_failed", failure)
            runCatching { notifications.cancel(requestedTag, STATUS_NOTIFICATION_ID) }
            statusTag = null
            statusFingerprint = null
            active = false
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.UNAVAILABLE,
                "notification_publish_failed:${failure.javaClass.simpleName}",
            )
            if (onPromptShown != null) {
                reportOnce(
                    snapshot.wire,
                    BridgePresentationWire.MODULE_CONNECTION,
                    BridgePresentationWire.UNAVAILABLE,
                    "status_notification_island_publish_failed:${failure.javaClass.simpleName}",
                )
            }
        }
    }

    private fun publishStatusCard(snapshot: RenderedSnapshot, requestedTag: String, onPromptShown: (() -> Unit)?) {
        val showsPrompt = onPromptShown != null
        notifications.ensureChannel(STATUS_CHANNEL_ID, "OpenRose 耳机状态")
        val card = content.statusCard(snapshot)
        val fingerprint = card.fingerprint(snapshot, requestedTag)
        if (active && statusFingerprint == fingerprint && !showsPrompt) return

        val builder = content.statusNotificationBuilder(snapshot, card, showsPrompt)
        // An update without island data is not known to remove a summary state already shown, and one with it changes
        // the island in place unnoticed; withdraw first to remove the island or to replay its entrance.
        val islandShown = active && statusIslandPosted
        if (islandShown && (!snapshot.statusIsland || showsPrompt)) {
            notifications.cancel(requestedTag, STATUS_NOTIFICATION_ID)
        }
        notifications.post(requestedTag, STATUS_NOTIFICATION_ID, builder.build())
        statusTag = requestedTag
        statusFingerprint = fingerprint
        active = true
        statusIslandPosted = snapshot.statusIsland
        reportOnce(
            snapshot.wire,
            BridgePresentationWire.STATUS_NOTIFICATION,
            BridgePresentationWire.CALLED,
            "notification_manager_notify_returned",
        )
        if (onPromptShown != null) {
            onPromptShown()
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.MODULE_CONNECTION,
                BridgePresentationWire.CALLED,
                "status_notification_island_notify_returned",
            )
        }
        if (card.controlReceiverMissing) {
            reportOnce(
                snapshot.wire,
                BridgePresentationWire.STATUS_NOTIFICATION,
                BridgePresentationWire.ACTION_FAILED,
                "notification_view_available_but_control_receiver_unavailable",
            )
        }
    }

    private fun prepareStatusTag(snapshot: RenderedSnapshot): String? {
        val tag = requestedStatusTag(snapshot)
        if (!snapshot.focusEnabled) {
            withdraw(snapshot.wire)
            return null
        }
        val unavailable = when {
            !notifications.available -> "notification_manager_unavailable"

            tag == null -> "missing_owned_notification_tag"

            else -> {
                if (statusTag != null && statusTag != tag) withdraw(snapshot.wire)
                if (notifications.hasNotificationPermission()) null else "target_process_notification_permission_missing"
            }
        }
        return if (unavailable == null) {
            statusTag = tag
            tag
        } else {
            withdraw(snapshot.wire)
            reportOnce(snapshot.wire, BridgePresentationWire.STATUS_NOTIFICATION, BridgePresentationWire.UNAVAILABLE, unavailable)
            null
        }
    }

    private fun requestedStatusTag(snapshot: RenderedSnapshot): String? {
        val tag = snapshot.presentation.getBundle(BridgePresentationWire.NOTIFICATION)?.getString(BridgePresentationWire.TAG)
        return tag?.takeIf { it.startsWith(STATUS_TAG_PREFIX) && it.length <= MAX_TAG_LENGTH }
    }

    /** A module prompt rides on the status notification's island whenever that notification is shown with one. */
    fun canMergePrompt(snapshot: RenderedSnapshot): Boolean {
        if (!snapshot.focusEnabled || !snapshot.statusIsland || !notifications.available) return false
        return requestedStatusTag(snapshot) != null
    }

    fun withdraw(snapshot: Bundle?) {
        val ownedTag = statusTag
        if (ownedTag != null && ownedTag.startsWith(STATUS_TAG_PREFIX)) {
            runCatching { notifications.cancel(ownedTag, STATUS_NOTIFICATION_ID) }
                .onSuccess {
                    if (active && snapshot != null) {
                        reportOnce(
                            snapshot,
                            BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.WITHDRAWN,
                            "owned_status_notification_cancel_returned",
                        )
                    }
                }
                .onFailure { failure ->
                    if (snapshot != null) {
                        reportOnce(
                            snapshot,
                            BridgePresentationWire.STATUS_NOTIFICATION,
                            BridgePresentationWire.ACTION_FAILED,
                            "status_notification_cancel:${failure.javaClass.simpleName}",
                        )
                    }
                }
        }
        statusTag = null
        statusFingerprint = null
        active = false
        statusIslandPosted = false
    }

    private companion object {
        const val STATUS_TAG_PREFIX = "openrose:"
        const val MAX_TAG_LENGTH = 256
    }
}

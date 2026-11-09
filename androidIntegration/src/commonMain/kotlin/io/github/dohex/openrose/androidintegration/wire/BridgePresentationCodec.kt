package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.SystemPresentationState

/** Encoded from the same authority capture as the device fields in its enclosing snapshot. */
fun SystemPresentationState.toBridgePresentation(): Map<String, Any?> = buildMap {
    put(BridgePresentationWire.MODE, islandMode.name)
    put(BridgePresentationWire.FOCUS_ENABLED, focusNotificationEnabled)
    put(BridgePresentationWire.ALLOW_ISLAND_FIELDS, allowIslandFields)
    put(BridgePresentationWire.STATUS_SUMMARY_ISLAND, statusSummaryIsland)
    put(BridgePresentationWire.STATUS_TARGET, statusNotificationTarget.name)
    if (showStatusNotification) {
        put(BridgePresentationWire.NOTIFICATION, mapOf(BridgePresentationWire.TAG to "openrose:$notificationTag"))
    }
    prompt?.let { prompt ->
        put(
            BridgePresentationWire.PROMPT,
            mapOf(
                BridgePresentationWire.PROMPT_ID to prompt.id,
                BridgePresentationWire.TRIGGER to prompt.trigger.name,
                BridgePresentationWire.MODE to prompt.mode.name,
                BridgePresentationWire.CLAIMED to prompt.claimed,
                BridgePresentationWire.DURATION_MILLIS to prompt.durationMillis,
            ),
        )
    }
}

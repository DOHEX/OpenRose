package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.androidintegration.SystemPresentationState

fun SystemPresentationState.toBridgePresentation(): Map<String, Any?> = buildMap {
    put(BridgePresentationWire.MODE, islandMode.name)
    put(BridgePresentationWire.STATUS_NOTIFICATION_ENABLED, statusNotificationEnabled)
    put(BridgePresentationWire.ALLOW_ISLAND_FIELDS, allowIslandFields)
    put(BridgePresentationWire.STATUS_SUMMARY_ISLAND, statusSummaryIsland)
    put(BridgePresentationWire.STATUS_TARGET, statusNotificationTarget.name)
    put(BridgePresentationWire.DYNAMIC_COLOR, dynamicColor)
    if (showStatusNotification) {
        put(BridgePresentationWire.NOTIFICATION, mapOf(BridgePresentationWire.TAG to "openrose:$notificationTag"))
    }
    prompt?.let { prompt ->
        put(
            BridgePresentationWire.PROMPT,
            mapOf(
                BridgePresentationWire.PROMPT_ID to prompt.id,
                BridgePresentationWire.TIMING to prompt.timing.name,
                BridgePresentationWire.MODE to prompt.mode.name,
                BridgePresentationWire.CLAIMED to prompt.claimed,
                BridgePresentationWire.DURATION_MILLIS to prompt.durationMillis,
            ),
        )
    }
}

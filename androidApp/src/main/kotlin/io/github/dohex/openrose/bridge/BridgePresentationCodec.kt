package io.github.dohex.openrose.bridge

import android.os.Bundle
import io.github.dohex.openrose.androidintegration.SystemPresentationState

/** Encoded from the same authority capture as the device fields in its enclosing snapshot. */
internal fun SystemPresentationState.toBridgePresentation(): Bundle = Bundle().apply {
    putString(BridgePresentationWire.MODE, islandMode.name)
    putBoolean(BridgePresentationWire.FOCUS_ENABLED, focusNotificationEnabled)
    putBoolean(BridgePresentationWire.ALLOW_ISLAND_FIELDS, allowIslandFields)
    if (showStatusNotification) putBundle(BridgePresentationWire.NOTIFICATION, Bundle().apply {
        putString(BridgePresentationWire.TAG, "openrose:$notificationTag")
    })
    connectionPrompt?.let { prompt -> putBundle(BridgePresentationWire.PROMPT, Bundle().apply {
        putString(BridgePresentationWire.PROMPT_ID, prompt.id)
        putString(BridgePresentationWire.MODE, prompt.mode.name)
        putBoolean(BridgePresentationWire.CLAIMED, prompt.claimed)
        putLong(BridgePresentationWire.DURATION_MILLIS, prompt.durationMillis)
    }) }
}

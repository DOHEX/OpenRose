package io.github.dohex.openrose.androidintegration

/** Android's `NotificationManager.IMPORTANCE_NONE`: a channel with this importance never shows its notifications. */
const val CHANNEL_IMPORTANCE_NONE = 0

/**
 * Whether the running notification can be seen: notifications are enabled app-wide and its channel is not
 * blocked. A missing channel ([channelImportance] null) is not blocked.
 */
fun runningNotificationVisible(enabled: Boolean, channelImportance: Int?): Boolean = enabled && channelImportance != CHANNEL_IMPORTANCE_NONE

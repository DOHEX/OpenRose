package io.github.dohex.openrose.androidintegration

const val CHANNEL_IMPORTANCE_NONE = 0

fun runningNotificationVisible(enabled: Boolean, channelImportance: Int?): Boolean = enabled && channelImportance != CHANNEL_IMPORTANCE_NONE

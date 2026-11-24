package io.github.dohex.openrose.androidintegration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunningNotificationVisibilityTest {
    @Test
    fun onlyABlockedChannelOrDisabledNotificationsHideTheRunningNotification() {
        assertFalse(runningNotificationVisible(enabled = true, channelImportance = 0))
        listOf(1, 2, 3, 4).forEach { assertTrue(runningNotificationVisible(enabled = true, channelImportance = it), "importance $it") }
        listOf(null, 0, 3).forEach { assertFalse(runningNotificationVisible(enabled = false, channelImportance = it), "importance $it") }
    }

    @Test
    fun aMissingChannelDoesNotHideTheNotification() {
        assertTrue(runningNotificationVisible(enabled = true, channelImportance = null))
    }
}

package io.github.dohex.openrose.androidintegration.ui

import io.github.dohex.openrose.androidintegration.BackgroundControlBlock

internal object BackgroundControlTags {
    const val SWITCH = "background-control.switch"

    const val STATUS_PREFIX = "background-control.status."
    const val STATUS_NOT_RUNNING = "${STATUS_PREFIX}not-running"
    const val STATUS_RUNNING = "${STATUS_PREFIX}running"
    const val NOTIFICATION_HIDDEN = "background-control.notification-hidden"
    const val RECOVERY_NEEDS_ASSOCIATION = "background-control.recovery.needs-association"
    const val RECOVERY_PRESENCE_PENDING = "background-control.recovery.presence-pending"
    const val RECOVERY_PRESENCE_REFUSED = "background-control.recovery.presence-refused"
    const val RECOVERY_ARMED = "background-control.recovery.armed"
    const val GRANT_BLUETOOTH = "background-control.grant-bluetooth"
    const val ALLOW_NOTIFICATION = "background-control.allow-notification"
    const val ASSOCIATE = "background-control.associate"
    const val APP_SETTINGS = "background-control.app-settings"

    fun blocked(reason: BackgroundControlBlock) = "${STATUS_PREFIX}blocked.${reason.name}"
}

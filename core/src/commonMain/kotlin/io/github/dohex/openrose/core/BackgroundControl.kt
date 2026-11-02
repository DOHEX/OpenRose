package io.github.dohex.openrose.core

/**
 * Why ordinary background control cannot run although the user enabled it. Each reason has its own
 * user action, so none of them is folded into a generic failure.
 */
enum class BackgroundControlBlock {
    /** Bluetooth permission is missing or revoked; a connected-device service cannot start without it. */
    BLUETOOTH_PERMISSION,

    /** The system refused to start a foreground service from the current background state. */
    BACKGROUND_START_RESTRICTED,

    /** The system refused the start for another reason, for example a missing service permission. */
    SERVICE_START_REFUSED,
}

/** What the platform reports about the service that carries ordinary background control. */
sealed interface BackgroundServiceFact {
    data object NotRunning : BackgroundServiceFact
    data object Running : BackgroundServiceFact
    data class StartRejected(val reason: BackgroundControlBlock) : BackgroundServiceFact
}

/** Whether the required running notification can actually be seen, independent of the focus notification setting. */
enum class RunningNotification { UNKNOWN, VISIBLE, HIDDEN }

data class BackgroundServiceFacts(
    val service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
    val notification: RunningNotification = RunningNotification.UNKNOWN,
)

/**
 * The user's saved intent, the permission and the service facts stay separate: saving the
 * preference never reads as control being ready.
 */
sealed interface BackgroundControlStatus {
    /** This host has no background service mechanism. */
    data object Unsupported : BackgroundControlStatus

    /** The user has not enabled background control. */
    data object Disabled : BackgroundControlStatus

    /** Enabled and startable, but no service is running yet; the host starts it from a user-visible entry. */
    data object NotRunning : BackgroundControlStatus

    data class Running(val notificationVisible: Boolean) : BackgroundControlStatus
    data class Blocked(val reason: BackgroundControlBlock) : BackgroundControlStatus
}

internal fun ControlState.deriveBackgroundControl(): BackgroundControlStatus {
    if (platform.hostPlatform == HostPlatform.WINDOWS || platform.hostPlatform == HostPlatform.LINUX) {
        return BackgroundControlStatus.Unsupported
    }
    // Background control belongs to ordinary control; the saved intent is kept but not in force under another way.
    if (!settings.backgroundControlEnabled || owner.selected != ControlWay.ORDINARY) return BackgroundControlStatus.Disabled
    if (platform.permission == PermissionState.REQUIRED || platform.permission == PermissionState.DENIED) {
        return BackgroundControlStatus.Blocked(BackgroundControlBlock.BLUETOOTH_PERMISSION)
    }
    return when (val service = platform.backgroundService.service) {
        BackgroundServiceFact.NotRunning -> BackgroundControlStatus.NotRunning
        BackgroundServiceFact.Running ->
            BackgroundControlStatus.Running(platform.backgroundService.notification != RunningNotification.HIDDEN)
        is BackgroundServiceFact.StartRejected -> BackgroundControlStatus.Blocked(service.reason)
    }
}

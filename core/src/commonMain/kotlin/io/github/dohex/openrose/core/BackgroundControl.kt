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

/**
 * Which public wake-up mechanisms the Android version offers. The bands follow the platform's real
 * differences and are never treated as one mechanism: before Android 12 there is no device-presence
 * API, from Android 12 a companion association is required, and Android 14 adds the typed
 * connected-device foreground service on top of that.
 */
enum class WakeBand { ANDROID_8_11, ANDROID_12_13, ANDROID_14_PLUS }

/** Companion-device association needed for presence wake-ups and the background foreground-service exemption (Android 12+). */
enum class CompanionAssociation { NOT_REQUIRED, MISSING, ACTIVE }

/** Whether the system has accepted the request to wake OpenRose when the associated earphones appear. */
enum class PresenceObservation { NOT_REQUIRED, NOT_OBSERVING, OBSERVING, REFUSED }

/**
 * What the platform can verify about reviving ordinary control after the process is gone. System
 * autostart and battery policy cannot be queried, so they are intentionally not represented here
 * and no state built from these facts claims that a ROM will really wake the app.
 */
data class WakeFacts(
    /** `null` when the host has no wake path or has not reported one. */
    val band: WakeBand? = null,
    val association: CompanionAssociation = CompanionAssociation.NOT_REQUIRED,
    val presence: PresenceObservation = PresenceObservation.NOT_REQUIRED,
)

data class BackgroundServiceFacts(
    val service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
    val notification: RunningNotification = RunningNotification.UNKNOWN,
    val wake: WakeFacts = WakeFacts(),
)

/** A prerequisite the user can complete for unattended recovery. */
enum class WakePrerequisite { BLUETOOTH_PERMISSION, COMPANION_ASSOCIATION, PRESENCE_OBSERVATION }

/** Readiness of automatic revival, kept apart from the saved intent and from the running service. */
sealed interface BackgroundRecovery {
    /** This host has no background revival mechanism. */
    data object Unsupported : BackgroundRecovery

    /** The user has not enabled background control, so no trigger may restore anything. */
    data object Disabled : BackgroundRecovery

    /** Saved settings are only available after first unlock; nothing is revived before it. */
    data object WaitingForUnlock : BackgroundRecovery

    /** Enabled, but these verifiable prerequisites are missing, so unattended revival is not ready. */
    data class Incomplete(val missing: List<WakePrerequisite>) : BackgroundRecovery

    /** Every verifiable prerequisite holds; ROM autostart and battery limits remain unverified. */
    data class Armed(val band: WakeBand) : BackgroundRecovery
}

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

private fun PlatformState.hasBackgroundService() = hostPlatform != HostPlatform.WINDOWS && hostPlatform != HostPlatform.LINUX

private fun PlatformState.bluetoothPermissionMissing() =
    permission == PermissionState.REQUIRED || permission == PermissionState.DENIED

internal fun ControlState.deriveBackgroundRecovery(): BackgroundRecovery {
    val wake = platform.backgroundService.wake
    val band = wake.band
    if (!platform.hasBackgroundService() || band == null) return BackgroundRecovery.Unsupported
    if (!platform.userUnlocked) return BackgroundRecovery.WaitingForUnlock
    if (!settings.backgroundControlEnabled) return BackgroundRecovery.Disabled
    val missing = buildList {
        if (platform.bluetoothPermissionMissing()) add(WakePrerequisite.BLUETOOTH_PERMISSION)
        if (wake.association == CompanionAssociation.MISSING) add(WakePrerequisite.COMPANION_ASSOCIATION)
        if (wake.presence == PresenceObservation.NOT_OBSERVING || wake.presence == PresenceObservation.REFUSED) {
            add(WakePrerequisite.PRESENCE_OBSERVATION)
        }
    }
    return if (missing.isEmpty()) BackgroundRecovery.Armed(band) else BackgroundRecovery.Incomplete(missing)
}

/**
 * Whether a wake-up trigger (boot, audio connection, device presence) or a user entry may start the
 * service now. Only the saved intent, first unlock and the Bluetooth permission decide this; a
 * missing association never blocks the attempt, because the system's refusal is then reported as it is.
 */
internal fun ControlState.deriveMayStartBackgroundService(): Boolean =
    owner.selected == ControlWay.ORDINARY && platform.hasBackgroundService() && platform.userUnlocked &&
        settings.backgroundControlEnabled &&
        !platform.bluetoothPermissionMissing()

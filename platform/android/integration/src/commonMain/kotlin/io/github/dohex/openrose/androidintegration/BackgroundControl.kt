package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HostPlatform
import io.github.dohex.openrose.core.PermissionState
import io.github.dohex.openrose.core.PlatformState

enum class BackgroundControlBlock {
    BLUETOOTH_PERMISSION,

    BACKGROUND_START_RESTRICTED,

    SERVICE_START_REFUSED,
}

sealed interface BackgroundServiceFact {
    data object NotRunning : BackgroundServiceFact
    data object Running : BackgroundServiceFact
    data class StartRejected(val reason: BackgroundControlBlock) : BackgroundServiceFact
}

enum class RunningNotification { UNKNOWN, VISIBLE, HIDDEN }

enum class WakeBand { ANDROID_8_11, ANDROID_12_13, ANDROID_14_PLUS }

enum class CompanionAssociation { NOT_REQUIRED, MISSING, ACTIVE }

enum class PresenceObservation { NOT_REQUIRED, NOT_OBSERVING, OBSERVING, REFUSED }

data class WakeFacts(
    val band: WakeBand? = null,
    val association: CompanionAssociation = CompanionAssociation.NOT_REQUIRED,
    val presence: PresenceObservation = PresenceObservation.NOT_REQUIRED,
)

data class BackgroundServiceFacts(
    val service: BackgroundServiceFact = BackgroundServiceFact.NotRunning,
    val notification: RunningNotification = RunningNotification.UNKNOWN,
    val wake: WakeFacts = WakeFacts(),
)

enum class WakePrerequisite { BLUETOOTH_PERMISSION, COMPANION_ASSOCIATION, PRESENCE_OBSERVATION }

sealed interface BackgroundRecovery {
    data object Unsupported : BackgroundRecovery

    data object Disabled : BackgroundRecovery

    data object WaitingForUnlock : BackgroundRecovery

    data class Incomplete(val missing: List<WakePrerequisite>) : BackgroundRecovery

    data class Armed(val band: WakeBand) : BackgroundRecovery
}

sealed interface BackgroundControlStatus {
    data object Unsupported : BackgroundControlStatus

    data object Disabled : BackgroundControlStatus

    data object NotRunning : BackgroundControlStatus

    data class Running(val notificationVisible: Boolean) : BackgroundControlStatus
    data class Blocked(val reason: BackgroundControlBlock) : BackgroundControlStatus
}

val IntegrationState.ordinaryBackgroundRequested: Boolean
    get() = owner.selected == ControlWay.ORDINARY && settings.backgroundControlEnabled

fun IntegrationState.backgroundControl(control: ControlState): BackgroundControlStatus {
    if (!control.platform.hasBackgroundService()) return BackgroundControlStatus.Unsupported
    if (!ordinaryBackgroundRequested) return BackgroundControlStatus.Disabled
    if (control.platform.bluetoothPermissionMissing()) {
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

private fun PlatformState.bluetoothPermissionMissing() = permission == PermissionState.REQUIRED || permission == PermissionState.DENIED

fun IntegrationState.backgroundRecovery(control: ControlState): BackgroundRecovery {
    val wake = platform.backgroundService.wake
    val band = wake.band
    if (!control.platform.hasBackgroundService() || band == null) return BackgroundRecovery.Unsupported
    if (!control.platform.userUnlocked) return BackgroundRecovery.WaitingForUnlock
    if (!ordinaryBackgroundRequested) return BackgroundRecovery.Disabled
    val missing = buildList {
        if (control.platform.bluetoothPermissionMissing()) add(WakePrerequisite.BLUETOOTH_PERMISSION)
        if (wake.association == CompanionAssociation.MISSING) add(WakePrerequisite.COMPANION_ASSOCIATION)
        if (wake.presence == PresenceObservation.NOT_OBSERVING || wake.presence == PresenceObservation.REFUSED) {
            add(WakePrerequisite.PRESENCE_OBSERVATION)
        }
    }
    return if (missing.isEmpty()) BackgroundRecovery.Armed(band) else BackgroundRecovery.Incomplete(missing)
}

fun IntegrationState.mayStartBackgroundService(control: ControlState): Boolean = ordinaryBackgroundRequested &&
    control.platform.hasBackgroundService() &&
    control.platform.userUnlocked &&
    !control.platform.bluetoothPermissionMissing()

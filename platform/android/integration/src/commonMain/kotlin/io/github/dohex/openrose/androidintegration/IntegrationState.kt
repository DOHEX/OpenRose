package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PlatformApplicability

data class IntegrationPlatformState(
    val module: ModuleAvailability = ModuleAvailability.Unavailable("尚未检测系统模块"),
    val bridge: SystemBridgeDiagnostics? = null,
    val systemIntegrationApplicability: PlatformApplicability = PlatformApplicability.UNKNOWN,
    val installedScopes: Set<String> = emptySet(),
    val backgroundService: BackgroundServiceFacts = BackgroundServiceFacts(),
    val dynamicColorApplicability: PlatformApplicability = PlatformApplicability.UNKNOWN,
    val unavailableStatusTargets: Map<StatusNotificationTarget, String> = emptyMap(),
)

data class IntegrationState(
    val settings: IntegrationSettings = IntegrationSettings(),
    val platform: IntegrationPlatformState = IntegrationPlatformState(),
    val owner: OwnerState = OwnerState(),
    val presentation: SystemPresentationState = SystemPresentationState(),
) {
    val dynamicColorInEffect: Boolean
        get() = settings.dynamicColor && platform.dynamicColorApplicability == PlatformApplicability.APPLICABLE

    val circulationAvailable: Boolean get() = owner.selected == ControlWay.MODULE
}

sealed interface ModuleAvailability {
    data object Available : ModuleAvailability
    data object NotEnabled : ModuleAvailability

    data class Unavailable(val reason: String, val cause: ModuleUnavailableCause? = null) : ModuleAvailability

    data object Unresponsive : ModuleAvailability
}

enum class ModuleUnavailableCause {
    FRAMEWORK_MISSING,

    FRAMEWORK_OUTDATED,

    HOST_NOT_FOUND,

    AUTHORIZATION_REFUSED,

    LINK_LOST,
}

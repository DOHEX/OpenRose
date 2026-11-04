package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.PlatformApplicability

/** Android system-integration facts the platform reports beside the core platform facts of [ControlState]. */
data class IntegrationPlatformState(
    val module: ModuleAvailability = ModuleAvailability.Unavailable("尚未检测系统模块"),
    val bridge: SystemBridgeDiagnostics? = null,
    /** Whether HyperOS system integration applies on this host, independent of integration readiness. */
    val systemIntegrationApplicability: PlatformApplicability = PlatformApplicability.UNKNOWN,
    /** Facts about the ordinary background-control service, separate from the saved intent in settings. */
    val backgroundService: BackgroundServiceFacts = BackgroundServiceFacts(),
)

/**
 * The Android integration's state, exposed beside the [ControlState] of the same control entry rather than
 * inside it: what the platform reports about the system module, who owns the control session, and the
 * system-facing view of the current device session.
 */
data class IntegrationState(
    val platform: IntegrationPlatformState = IntegrationPlatformState(),
    /** Selected control way, whether its owner is ready, and who actually holds the session. */
    val owner: OwnerState = OwnerState(),
    val presentation: SystemPresentationState = SystemPresentationState(),
)

sealed interface ModuleAvailability {
    data object Available : ModuleAvailability
    data object NotEnabled : ModuleAvailability
    /** [reason] is for display; [cause] is what a surface may branch on, null when no next step is known. */
    data class Unavailable(val reason: String, val cause: ModuleUnavailableCause? = null) : ModuleAvailability

    /** Authorised and reachable, but the host stopped answering; unlike [Unavailable] a process that is merely absent is not meant. */
    data object Unresponsive : ModuleAvailability
}

/** Why the system module cannot act, where the application can tell the user what to do about it. */
enum class ModuleUnavailableCause {
    /** No Xposed framework service has reached the application. */
    FRAMEWORK_MISSING,

    /** The framework service speaks an API older than the module needs. */
    FRAMEWORK_OUTDATED,

    /** No module host answered where the module should have loaded it. */
    HOST_NOT_FOUND,

    /** The host refused this application's authorisation. */
    AUTHORIZATION_REFUSED,

    /** A working link to the host ended. */
    LINK_LOST,
}

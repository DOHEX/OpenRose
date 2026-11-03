package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.transport.DeviceId

/**
 * Who is calling the module host, derived from the verified caller and never from what a payload claims.
 * [HOST_PRESENTATION] is the module's own presentation code inside the host process, identified by the
 * host's uid; the other roles are other processes verified by uid and package.
 */
enum class ClientRole { APP, MILINK, XIAOMI_BLUETOOTH, HOST_PRESENTATION }

/** The caller as the platform verified it (Binder uid/pid and the packages owning that uid). */
data class CallerIdentity(
    val uid: Int,
    val pid: Int,
    val packages: Set<String>,
    val processName: String? = null,
)

/** Decides which role, if any, a verified caller may hold. Discovering the endpoint grants nothing. */
fun interface CallerAuthority {
    fun roleOf(identity: CallerIdentity): ClientRole?
}

/** Every operation an authorised client can ask the owner for. */
sealed interface HostCommand {
    data object RefreshBondedDevices : HostCommand
    data object StartScan : HostCommand
    data object StopScan : HostCommand
    data class Connect(val deviceId: DeviceId) : HostCommand
    data class ConnectFromSystem(val deviceId: DeviceId) : HostCommand
    data class ConnectForAddDevice(val deviceId: DeviceId) : HostCommand
    data object Disconnect : HostCommand
    /** [reference] null targets whatever session is current; non-null is rejected unless it still is. */
    data class Set(val reference: SessionReference?, val setting: DeviceSetting, val value: FieldValue) : HostCommand
    data class UpdateSettings(val settings: UserSettings) : HostCommand
    data class ClaimPrompt(val reference: SessionReference, val promptId: String) : HostCommand
}

/** Commands a role may submit. System entries only ever drive noise control and prompt claims. */
fun ClientRole.allows(command: HostCommand): Boolean = when (this) {
    ClientRole.APP -> true
    ClientRole.MILINK, ClientRole.XIAOMI_BLUETOOTH, ClientRole.HOST_PRESENTATION -> when (command) {
        is HostCommand.Set -> command.reference != null && command.setting == DeviceSetting.NOISE_MODE
        is HostCommand.ClaimPrompt -> this != ClientRole.MILINK
        else -> false
    }
}

/**
 * Whether this role renders the connection prompt of [mode]: the module's own SystemUI presentation in the
 * host process for [IslandMode.MODULE], the Xiaomi Bluetooth process for [IslandMode.OFFICIAL], nobody for
 * [IslandMode.OFF]. The island mode is a presentation setting and has nothing to do with the control way.
 */
fun ClientRole.presents(mode: IslandMode): Boolean = when (mode) {
    IslandMode.MODULE -> this == ClientRole.HOST_PRESENTATION
    IslandMode.OFFICIAL -> this == ClientRole.XIAOMI_BLUETOOTH
    IslandMode.OFF -> false
}

enum class HostRejection {
    /** The caller is not allowed to use the host at all. */
    UNAUTHORIZED,
    /** The lease is unknown, expired, or belongs to a different caller. */
    INVALID_LEASE,
    /** The caller addressed an owner generation that is not the current one. */
    STALE_GENERATION,
    /** The caller's role does not include this operation. */
    FORBIDDEN,
    /** No owner is active, so there is nothing to command. */
    NO_OWNER,
    /** The owner could not be released, so authority was not given up. */
    RELEASE_FAILED,
    /** The request is not valid for the host's current state. */
    INVALID_REQUEST,
}

/** A lease binds one verified caller to one host incarnation; owner generations are checked per call. */
data class HostLease(
    val id: String,
    val role: ClientRole,
    val incarnation: String,
)

sealed interface HostOpenResult {
    data class Opened(val lease: HostLease, val status: HostStatus) : HostOpenResult
    data class Refused(val rejection: HostRejection, val message: String) : HostOpenResult
}

/** Where the host's single ownership currently stands. */
sealed interface HostStatus {
    /** An owner holds the session. */
    data class Active(val generation: OwnerGeneration, val holder: OwnerHolder) : HostStatus

    /** Nobody holds it: not unlocked, not selected, disabled, or released by a switch request. */
    data class Inactive(
        val incarnation: String,
        val lastGeneration: OwnerGeneration?,
        val reason: String,
    ) : HostStatus
}

sealed interface HostCallResult {
    data class Operation(val result: OperationResult) : HostCallResult
    data class Flag(val value: Boolean) : HostCallResult
    data class Rejected(val rejection: HostRejection, val message: String) : HostCallResult
}

/**
 * Proof that a particular switch request released the owner. [generation] names the owner that
 * was released; [predecessorEnded] is set when the requester's named owner belonged to an earlier
 * host incarnation, i.e. a process that no longer exists.
 */
data class ReleaseReceipt(
    val requestId: String,
    val incarnation: String,
    val generation: OwnerGeneration?,
    val predecessorEnded: Boolean = false,
)

sealed interface ReleaseResult {
    data class Released(val receipt: ReleaseReceipt) : ReleaseResult
    data class Refused(val rejection: HostRejection, val message: String, val current: HostStatus? = null) : ReleaseResult
}

sealed interface AcquireResult {
    data class Acquired(val generation: OwnerGeneration, val holder: OwnerHolder) : AcquireResult
    data class Refused(val rejection: HostRejection, val message: String) : AcquireResult
}

/**
 * What the host knows about its system-entry consumers; reported by them, never trusted as device truth.
 * Shown in diagnostics only.
 */
data class HostFacts(
    val endpoints: Map<String, SystemEndpointDiagnostics> = emptyMap(),
    val presentation: Map<String, String> = emptyMap(),
)

/** The owner's published state, tagged with the generation that produced it. */
data class HostFrame(
    val generation: OwnerGeneration,
    val holder: OwnerHolder,
    val platform: io.github.dohex.openrose.core.wire.HostPlatformFacts,
    val state: ControlState,
    val facts: HostFacts = HostFacts(),
)

/** The owner's current state for a consumer that only needs to read it. */
data class OwnerView(val generation: OwnerGeneration, val state: ControlState)

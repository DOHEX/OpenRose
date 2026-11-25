package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.HostPlatformFacts
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.HeadsetCommand
import io.github.dohex.openrose.core.OperationResult
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.transport.DeviceId

enum class ClientRole { APP, MILINK, XIAOMI_BLUETOOTH, HOST_PRESENTATION }

data class CallerIdentity(
    val uid: Int,
    val pid: Int,
    val packages: Set<String>,
    val processName: String? = null,
)

fun interface CallerAuthority {
    fun roleOf(identity: CallerIdentity): ClientRole?
}

sealed interface HostCommand {
    data object RefreshBondedDevices : HostCommand
    data object StartScan : HostCommand
    data object StopScan : HostCommand
    data class Connect(val deviceId: DeviceId) : HostCommand
    data class ConnectFromSystem(val deviceId: DeviceId) : HostCommand
    data class ConnectForAddDevice(val deviceId: DeviceId) : HostCommand
    data object Disconnect : HostCommand

    data class Set(val reference: SessionReference?, val command: HeadsetCommand) : HostCommand
    data class ClaimPrompt(val reference: SessionReference, val promptId: String) : HostCommand
}

fun ClientRole.allows(command: HostCommand): Boolean = when (this) {
    ClientRole.APP -> true

    ClientRole.MILINK, ClientRole.XIAOMI_BLUETOOTH, ClientRole.HOST_PRESENTATION -> when (command) {
        is HostCommand.Set -> command.reference != null && command.command is HeadsetCommand.NoiseMode
        is HostCommand.ClaimPrompt -> this != ClientRole.MILINK
        else -> false
    }
}

fun ClientRole.presents(mode: IslandMode): Boolean = when (mode) {
    IslandMode.OPENROSE_STYLE -> this == ClientRole.HOST_PRESENTATION
    IslandMode.SYSTEM_STYLE -> this == ClientRole.XIAOMI_BLUETOOTH
    IslandMode.OFF -> false
}

enum class HostRejection {
    UNAUTHORIZED,

    INVALID_LEASE,

    STALE_GENERATION,

    FORBIDDEN,

    NO_OWNER,

    RELEASE_FAILED,

    INVALID_REQUEST,
}

data class HostLease(
    val id: String,
    val role: ClientRole,
    val incarnation: String,
)

sealed interface HostOpenResult {
    data class Opened(val lease: HostLease, val status: HostStatus) : HostOpenResult
    data class Refused(val rejection: HostRejection, val message: String) : HostOpenResult
}

sealed interface HostStatus {
    data class Active(val generation: OwnerGeneration, val holder: OwnerHolder) : HostStatus

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

data class HostFacts(
    val endpoints: Map<String, SystemEndpointDiagnostics> = emptyMap(),
    val presentation: Map<String, String> = emptyMap(),
)

data class HostFrame(
    val generation: OwnerGeneration,
    val holder: OwnerHolder,
    val platform: HostPlatformFacts,
    val state: ControlState,
    val presentation: SystemPresentationState,
    val facts: HostFacts = HostFacts(),
)

data class OwnerView(
    val generation: OwnerGeneration,
    val state: ControlState,
    val presentation: SystemPresentationState,
    val settings: IntegrationSettings,
)

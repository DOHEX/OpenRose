package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.SessionReference

/**
 * One owner lifetime. [incarnation] identifies the host process instance that created it and
 * [sequence] increases for every owner that instance activates, so a late callback, a cached
 * snapshot or a reference from before a restart can be recognised as belonging to another owner.
 */
data class OwnerGeneration(val incarnation: String, val sequence: Long) {
    override fun toString() = "$incarnation#$sequence"
}

/**
 * Where an owner instance's device session ids start. Every ordinary session and every module owner
 * counts up from its own base, so a [SessionReference] taken from one owner instance is never equal to
 * a reference into another, whether it is the next ordinary session or the other control way.
 */
internal object SessionIdBase {
    private const val MODULE_BAND = 1L shl 62

    fun ordinary(sequence: Long): Long = sequence shl 32
    fun module(sequence: Long): Long = MODULE_BAND or (sequence shl 32)
}

/** A user request to move control authority from [from] to [to]; receipts must echo [id]. */
data class SwitchRequest(
    val id: String,
    val from: ControlWay,
    val to: ControlWay,
    /** The module owner the requester last observed; null when none was ever observed. */
    val fromGeneration: OwnerGeneration? = null,
)

/** The stage at which an owner could not be reached, authorised, started or released. */
enum class OwnerFailureStage {
    SELECTION_PERSISTENCE, DISCOVERY, AUTHORIZATION, CONNECTION, RELEASE, ACTIVATION, OWNER_RUNTIME,
}

/** The actual holder of control authority, as reported by that holder itself. */
data class OwnerHolder(
    val way: ControlWay,
    val generation: OwnerGeneration,
    val packageName: String,
    val processName: String,
    val uid: Int? = null,
    val pid: Int? = null,
)

/** Whether the selected owner can act now. Selection and earphone connection are separate facts. */
sealed interface OwnerReadiness {
    /** The selected owner holds the session and accepts commands. */
    data object Ready : OwnerReadiness

    /** The selected owner is being established. */
    data object Starting : OwnerReadiness

    /** No owner is meant to be active, e.g. module control was disabled or the user is still locked. */
    data class Idle(val reason: String) : OwnerReadiness

    /** The selected owner cannot be used; the previous failure stage is kept for diagnosis. */
    data class Unavailable(val stage: OwnerFailureStage, val reason: String) : OwnerReadiness

    data class Switching(val request: SwitchRequest) : OwnerReadiness

    /** Authority was not moved because release of the previous owner could not be confirmed. */
    data class SwitchBlocked(
        val request: SwitchRequest,
        val stage: OwnerFailureStage,
        val reason: String,
    ) : OwnerReadiness
}

/** What the last switch did, so callers can say why nothing changed. */
sealed interface HandoffOutcome {
    val request: SwitchRequest

    data class Completed(override val request: SwitchRequest) : HandoffOutcome

    /** The new owner was not established, and no second owner was created. */
    data class Blocked(
        override val request: SwitchRequest,
        val stage: OwnerFailureStage,
        val reason: String,
    ) : HandoffOutcome
}

data class OwnerState(
    /** The user's saved choice. It is not evidence that the owner exists. */
    val selected: ControlWay = ControlWay.ORDINARY,
    val readiness: OwnerReadiness = OwnerReadiness.Ready,
    /** Who actually holds the session; null while nobody does. */
    val holder: OwnerHolder? = null,
    val lastHandoff: HandoffOutcome? = null,
)

/** Owner selection and explicit handoff, beyond the per-device operations of [ControlRuntime]. */
interface ControlOwnerSwitching {
    /** Moves the single control session to [way]; the previous owner is released first. */
    suspend fun selectControlWay(way: ControlWay): HandoffOutcome
}

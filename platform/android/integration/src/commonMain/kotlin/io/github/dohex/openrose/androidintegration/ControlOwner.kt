package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlRuntime
import io.github.dohex.openrose.core.SessionReference

data class OwnerGeneration(val incarnation: String, val sequence: Long) {
    override fun toString() = "$incarnation#$sequence"
}

internal object SessionIdBase {
    private const val MODULE_BAND = 1L shl 62

    fun app(sequence: Long): Long = sequence shl 32
    fun module(sequence: Long): Long = MODULE_BAND or (sequence shl 32)
}

data class SwitchRequest(
    val id: String,
    val from: ControlWay,
    val to: ControlWay,
    val fromGeneration: OwnerGeneration? = null,
)

enum class OwnerFailureStage {
    SELECTION_PERSISTENCE,
    DISCOVERY,
    AUTHORIZATION,
    CONNECTION,
    RELEASE,
    ACTIVATION,
    OWNER_RUNTIME,
}

data class OwnerHolder(
    val way: ControlWay,
    val generation: OwnerGeneration,
    val packageName: String,
    val processName: String,
    val uid: Int? = null,
    val pid: Int? = null,
)

sealed interface OwnerReadiness {
    data object Ready : OwnerReadiness

    data object Starting : OwnerReadiness

    data class Idle(val reason: String) : OwnerReadiness

    data class Unavailable(val stage: OwnerFailureStage, val reason: String) : OwnerReadiness

    data class Switching(val request: SwitchRequest) : OwnerReadiness

    data class SwitchBlocked(
        val request: SwitchRequest,
        val stage: OwnerFailureStage,
        val reason: String,
    ) : OwnerReadiness
}

sealed interface HandoffOutcome {
    val request: SwitchRequest

    data class Completed(override val request: SwitchRequest) : HandoffOutcome

    data class Blocked(
        override val request: SwitchRequest,
        val stage: OwnerFailureStage,
        val reason: String,
    ) : HandoffOutcome
}

data class OwnerState(
    val selected: ControlWay = ControlWay.APP,
    val readiness: OwnerReadiness = OwnerReadiness.Ready,
    val holder: OwnerHolder? = null,
    val lastHandoff: HandoffOutcome? = null,
)

interface ControlOwnerSwitching {
    suspend fun selectControlWay(way: ControlWay): HandoffOutcome
}

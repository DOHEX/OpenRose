package io.github.dohex.openrose.androidintegration.scope

/** What a restart reports while it runs: the operation's current phase, or one scope's final result. */
sealed interface ScopeRestartUpdate

/**
 * Authorizing: Root is being requested. Signalling: Root was granted and the chosen scopes are being
 * signalled together. Waiting: every signalled scope shares one deadline for its new process to appear;
 * it is reported again each time [Waiting.secondsLeft] drops.
 */
sealed interface ScopeRestartPhase : ScopeRestartUpdate {
    data object Authorizing : ScopeRestartPhase
    data object Signalling : ScopeRestartPhase
    data class Waiting(val secondsLeft: Int) : ScopeRestartPhase
}

/**
 * RESTARTED: a new process of the scope appeared after the old one was signalled. TERMINATED: the old
 * process is gone but none came back in time (on-demand processes start only when needed). Neither
 * proves the module loaded.
 */
enum class ScopeRestartStatus { RESTARTED, TERMINATED, NOT_RUNNING, FAILED }
data class ScopeRestartResult(val target: String, val status: ScopeRestartStatus, val detail: String? = null) : ScopeRestartUpdate

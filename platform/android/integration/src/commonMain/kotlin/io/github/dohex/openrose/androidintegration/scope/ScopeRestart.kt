package io.github.dohex.openrose.androidintegration.scope

sealed interface ScopeRestartUpdate

sealed interface ScopeRestartPhase : ScopeRestartUpdate {
    data object Authorizing : ScopeRestartPhase
    data object Signalling : ScopeRestartPhase
    data class Waiting(val secondsLeft: Int) : ScopeRestartPhase
}

enum class ScopeRestartStatus { RESTARTED, TERMINATED, NOT_RUNNING, FAILED }
data class ScopeRestartResult(val target: String, val status: ScopeRestartStatus, val detail: String? = null) : ScopeRestartUpdate

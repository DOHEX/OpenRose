package io.github.dohex.openrose.core

/** Observed platform facts; framework presence alone never establishes a live system bridge. */
data class SystemBridgeDiagnostics(
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val frameworkApi: Int? = null,
    val targetPackage: String? = null,
    val targetProcess: String? = null,
    val componentVersion: String? = null,
    val componentMatch: String? = null,
    val ipc: BridgeIpcState = BridgeIpcState.DISCONNECTED,
    val session: BridgeSessionState = BridgeSessionState.NONE,
    val detail: String? = null,
)

enum class BridgeIpcState { DISCONNECTED, WAITING, AUTHENTICATED, TARGET_INCOMPATIBLE, REJECTED }
enum class BridgeSessionState { NONE, WAITING_CONFIRMATION, READY, STALE }

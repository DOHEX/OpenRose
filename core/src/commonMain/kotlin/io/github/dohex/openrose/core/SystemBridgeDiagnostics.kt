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
    /** Target-process calls and compatibility failures; these never assert visible SystemUI output. */
    val presentation: Map<String, String> = emptyMap(),
    val endpoints: Map<String, SystemEndpointDiagnostics> = emptyMap(),
)

data class SystemEndpointDiagnostics(
    val targetPackage: String,
    val targetProcess: String,
    val componentVersion: String,
    val entryMatch: String,
    val ipc: BridgeIpcState,
    val features: Map<String, String> = emptyMap(),
)

enum class BridgeIpcState { DISCONNECTED, WAITING, AUTHENTICATED, TARGET_INCOMPATIBLE, REJECTED }
enum class BridgeSessionState { NONE, WAITING_CONFIRMATION, READY, STALE }

package io.github.dohex.openrose.core

/** Facts supplied by the platform adapter, independent of a UI component library. */
data class PlatformState(
    val permission: PermissionState,
    val transport: TransportAvailability = TransportAvailability.Unavailable("平台传输尚未实现"),
    val module: ModuleAvailability = ModuleAvailability.Unavailable("尚未检测系统模块"),
)

enum class PermissionState {
    NOT_REQUIRED,
    REQUIRED,
    GRANTED,
    DENIED,
}

sealed interface TransportAvailability {
    data object Available : TransportAvailability
    data object Disabled : TransportAvailability
    data class Unavailable(val reason: String) : TransportAvailability
}

sealed interface ModuleAvailability {
    data object Available : ModuleAvailability
    data object NotEnabled : ModuleAvailability
    data class Unavailable(val reason: String) : ModuleAvailability
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
}

data class ControlState(
    val platform: PlatformState,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val settings: UserSettings = UserSettings(),
)

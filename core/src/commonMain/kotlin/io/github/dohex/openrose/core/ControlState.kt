package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId

/** Facts supplied by the platform adapter, independent of a UI component library. */
data class PlatformState(
    val permission: PermissionState,
    val transport: TransportAvailability = TransportAvailability.Unavailable("平台传输尚未实现"),
    val module: ModuleAvailability = ModuleAvailability.Unavailable("尚未检测系统模块"),
)

enum class PermissionState { NOT_REQUIRED, REQUIRED, GRANTED, DENIED }

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

enum class ConnectionPhase { CONNECTING, DISCOVERING, SUBSCRIBING, INITIALIZING, READY }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceId: DeviceId, val sessionId: Long, val phase: ConnectionPhase) : ConnectionState
    data class Connected(
        val deviceId: DeviceId,
        val sessionId: Long,
        val profile: ModelProfile,
        val phase: ConnectionPhase = ConnectionPhase.READY,
    ) : ConnectionState
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Failed(val error: ControlError) : ScanState
}

data class DeviceCandidate(
    val deviceId: DeviceId,
    val name: String?,
    val rssi: Int?,
    val profile: ModelProfile?,
    val manufacturerDataHex: String,
)

enum class ControlErrorCode {
    PERMISSION_REQUIRED, TRANSPORT_UNAVAILABLE, NOT_READY, UNSUPPORTED, INVALID_ARGUMENT, NOT_APPLIED, TIMEOUT, DISCONNECTED, FAILED,
}

data class ControlError(val code: ControlErrorCode, val message: String)

sealed interface OperationResult {
    data object Success : OperationResult
    data class Failure(val error: ControlError) : OperationResult
}

enum class DeviceSetting { NOISE_MODE, ANC_LEVEL, TRANSPARENCY_LEVEL, NOISE_CYCLE, EQ, GAME_MODE, AUDIO_MODE, MULTIPOINT, TOUCH_ENABLED, EAR_DETECTION, TOUCH_BINDING, PROMPT_VOLUME, CUSTOM_EQ, GAIN, HEARING_PROTECTION }

/** Transport acceptance and device state confirmation are separate facts. */
enum class SettingStage { SUBMITTED, PARTIALLY_WRITTEN, WRITTEN, CONFIRMED, NOT_APPLIED, TIMED_OUT, DISCONNECTED, FAILED }

data class SettingOperation(
    val id: Long,
    val sessionId: Long,
    val setting: DeviceSetting,
    val requestedValue: FieldValue,
    val stage: SettingStage,
    val error: ControlError? = null,
    val writtenFrames: Int = 0,
    val totalFrames: Int = 0,
    val confirmedFields: Set<DeviceField> = emptySet(),
)

data class DeviceCapabilities(
    val readableFields: Set<DeviceField>,
    val writableSettings: Set<DeviceSetting> = emptySet(),
    val noiseControl: NoiseControlCapabilities? = null,
    val equalizer: EqualizerCapabilities? = null,
    val touchControl: TouchControlCapabilities? = null,
    val audioControl: AudioControlCapabilities? = null,
    val promptVolumeLevels: Set<Int> = emptySet(),
    val gainLevels: Set<Int> = emptySet(),
    val hearingProtectionLevels: Set<Int> = emptySet(),
)

data class DeviceState(
    val deviceId: DeviceId,
    val sessionId: Long,
    val profile: ModelProfile,
    val capabilities: DeviceCapabilities,
    val fields: Map<DeviceField, FieldReading>,
    val fieldErrors: Map<DeviceField, ControlError> = emptyMap(),
    /** Recent transport payloads, retained for diagnosis rather than interpreted as confirmed fields. */
    val rawNotifications: List<String> = emptyList(),
    val unknownNotifications: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class ControlState(
    val platform: PlatformState,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val settings: UserSettings = UserSettings(),
    val scan: ScanState = ScanState.Idle,
    val candidates: List<DeviceCandidate> = emptyList(),
    val device: DeviceState? = null,
    val lastError: ControlError? = null,
    /** Recent operations, including terminal outcomes across a disconnect. */
    val settingOperations: List<SettingOperation> = emptyList(),
)

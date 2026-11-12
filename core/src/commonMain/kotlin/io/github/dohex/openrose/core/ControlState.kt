package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.TransportSelection
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/** Facts supplied by the platform adapter, independent of a UI component library. */
data class PlatformState(
    val permission: PermissionState,
    val transport: TransportAvailability = TransportAvailability.Unavailable("平台传输尚未实现"),
    /** The host identity is a platform fact, separate from the transport state. */
    val hostPlatform: HostPlatform = HostPlatform.UNKNOWN,
    /** Whether this host has an implemented control transport where a transport preference has effect. */
    val controlTransportApplicability: PlatformApplicability = PlatformApplicability.UNKNOWN,
    /**
     * True once the saved user settings are restored after first unlock. Until then the runtime
     * observes audio connections but starts no automatic control, and the connection that
     * predates this fact is connected as soon as it turns true.
     */
    val userUnlocked: Boolean = true,
)

enum class HostPlatform { UNKNOWN, ANDROID, WINDOWS, LINUX }

/** Explicitly keeps unknown platform facts separate from supported and unsupported features. */
enum class PlatformApplicability { UNKNOWN, NOT_APPLICABLE, APPLICABLE }

enum class PermissionState { NOT_REQUIRED, REQUIRED, GRANTED, DENIED }

sealed interface TransportAvailability {
    data object Available : TransportAvailability
    data object Disabled : TransportAvailability
    data class Unavailable(val reason: String) : TransportAvailability
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
    val classicAddress: String? = null,
)

enum class ControlErrorCode {
    PERMISSION_REQUIRED,
    TRANSPORT_UNAVAILABLE,
    NOT_READY,
    UNSUPPORTED,
    INVALID_ARGUMENT,
    NOT_APPLIED,
    TIMEOUT,
    DISCONNECTED,
    FAILED,
}

data class ControlError(val code: ControlErrorCode, val message: String)

sealed interface OperationResult {
    data object Success : OperationResult
    data class Failure(val error: ControlError) : OperationResult
}

enum class DeviceSetting {
    NOISE_MODE,
    ANC_LEVEL,
    TRANSPARENCY_LEVEL,
    NOISE_CYCLE,
    EQ,
    GAME_MODE,
    AUDIO_MODE,
    MULTIPOINT,
    TOUCH_ENABLED,
    EAR_DETECTION,
    TOUCH_BINDING,
    PROMPT_VOLUME,
    CUSTOM_EQ,
    GAIN,
    HEARING_PROTECTION,
    BASS_BOOST,
    PROMPT_LANGUAGE,
    SPATIAL_AUDIO,
}

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
    val confirmedFields: PersistentSet<DeviceField> = persistentSetOf(),
)

data class DeviceCapabilities(
    val readableFields: PersistentSet<DeviceField>,
    val writableSettings: PersistentSet<DeviceSetting> = persistentSetOf(),
    val noiseControl: NoiseControlCapabilities? = null,
    val equalizer: EqualizerCapabilities? = null,
    val touchControl: TouchControlCapabilities? = null,
    val audioControl: AudioControlCapabilities? = null,
    val promptVolumeLevels: PersistentSet<Int> = persistentSetOf(),
    val gainLevels: PersistentSet<Int> = persistentSetOf(),
    val hearingProtectionLevels: PersistentSet<Int> = persistentSetOf(),
    val bassBoostLevels: PersistentSet<Int> = persistentSetOf(),
    val promptLanguages: PersistentSet<String> = persistentSetOf(),
)

data class DeviceState(
    val deviceId: DeviceId,
    val sessionId: Long,
    val profile: ModelProfile,
    val capabilities: DeviceCapabilities,
    val fields: PersistentMap<DeviceField, FieldReading>,
    val fieldErrors: PersistentMap<DeviceField, ControlError> = persistentMapOf(),
    /** Recent transport payloads, retained for diagnosis rather than interpreted as confirmed fields. */
    val rawNotifications: PersistentList<String> = persistentListOf(),
    val unknownNotifications: PersistentList<String> = persistentListOf(),
    val warnings: PersistentList<String> = persistentListOf(),
    /** An independently identified BR/EDR address, when the model advertisement provides it. */
    val classicAddress: String? = null,
)

/** Opaque reference to one device session; a caller holding one may address that session and no other. */
data class SessionReference(val deviceId: DeviceId, val sessionId: Long)

data class ControlState(
    val platform: PlatformState,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val settings: UserSettings = UserSettings(),
    val scan: ScanState = ScanState.Idle,
    val candidates: PersistentList<DeviceCandidate> = persistentListOf(),
    val bondedDevices: PersistentList<DeviceCandidate> = persistentListOf(),
    val device: DeviceState? = null,
    val lastError: ControlError? = null,
    /** Recent operations, including terminal outcomes across a disconnect. */
    val settingOperations: PersistentList<SettingOperation> = persistentListOf(),
    val transportSelection: TransportSelection? = null,
    val connectionRecovery: ConnectionRecovery? = null,
    val connectionTarget: DeviceCandidate? = null,
) {
    /** The ready device session, present only while the connection and the device state agree on it. */
    val readySession: SessionReference?
        get() {
            val connected = connection as? ConnectionState.Connected ?: return null
            val current = device ?: return null
            if (current.deviceId != connected.deviceId || current.sessionId != connected.sessionId ||
                current.profile != connected.profile
            ) {
                return null
            }
            return SessionReference(current.deviceId, current.sessionId)
        }
}

data class ConnectionRecovery(
    val sessionId: Long,
    val reason: io.github.dohex.openrose.core.transport.TransportRecoveryReason,
    val attempt: Int = 1,
)

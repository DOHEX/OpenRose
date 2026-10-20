package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue

/** A normalized field value that preserves the difference between missing and unknown data. */
sealed interface PresentationReading<out T> {
    data object Loading : PresentationReading<Nothing>
    data object Unknown : PresentationReading<Nothing>
    data object NotProvided : PresentationReading<Nothing>
    data class Known<T>(val value: T) : PresentationReading<T>
}

data class SystemBatteryState(
    val left: PresentationReading<Int>,
    val right: PresentationReading<Int>,
    val case: PresentationReading<Int>,
)

data class SystemNoiseState(
    val mode: PresentationReading<String>,
    val ancLevel: PresentationReading<Int>,
    val transparencyLevel: PresentationReading<Int>,
)

/** A short connection event which must be claimed before an adapter triggers SystemUI. */
data class ConnectionPrompt(
    val id: String,
    val mode: IslandMode,
    val reference: SessionReference,
    val claimed: Boolean = false,
    val durationMillis: Long,
)

/** Stable, normalized system-facing view of the current ready device session. */
data class SystemPresentationState(
    val reference: SessionReference? = null,
    /** Stable for this device so adapters update one status notification over the session. */
    val notificationTag: String? = null,
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    /** False when every island-capable output must omit island content. */
    val allowIslandFields: Boolean = false,
    val battery: SystemBatteryState? = null,
    val noise: SystemNoiseState? = null,
    val actions: Set<DeviceSetting> = emptySet(),
    val connectionPrompt: ConnectionPrompt? = null,
) {
    val showStatusNotification: Boolean
        get() = reference != null && focusNotificationEnabled
}

/** Rebuilds the system view from one ControlState capture, retaining only a live matching prompt. */
internal fun ControlState.withSystemPresentation(): ControlState {
    val connected = connection as? ConnectionState.Connected
    val currentDevice = device?.takeIf {
        connected != null && it.deviceId == connected.deviceId && it.sessionId == connected.sessionId &&
            it.profile == connected.profile
    }
    val reference = currentDevice?.let { SessionReference(it.deviceId, it.sessionId) }
    val mode = settings.islandMode
    val allowIslandFields = mode != IslandMode.OFF &&
        (mode != IslandMode.MODULE || IslandTrigger.CONNECTION in settings.islandTriggers)
    val prompt = presentation.connectionPrompt?.takeIf {
        reference != null && it.reference == reference && it.mode == mode && settings.allowsConnectionPrompt()
    }
    val state = currentDevice?.let { device ->
        SystemPresentationState(
            reference = reference,
            notificationTag = device.deviceId.value,
            islandMode = mode,
            focusNotificationEnabled = settings.focusNotificationEnabled,
            allowIslandFields = allowIslandFields,
            battery = SystemBatteryState(
                left = device.fields.reading(DeviceField.LEFT_BATTERY).normalizedBattery(),
                right = device.fields.reading(DeviceField.RIGHT_BATTERY).normalizedBattery(),
                case = device.fields.reading(DeviceField.CASE_BATTERY).normalizedBattery(),
            ),
            noise = SystemNoiseState(
                mode = device.fields.reading(DeviceField.NOISE_MODE).normalizedText(),
                ancLevel = device.fields.reading(DeviceField.ANC_LEVEL).normalizedNumber(),
                transparencyLevel = device.fields.reading(DeviceField.TRANSPARENCY_LEVEL).normalizedNumber(),
            ),
            actions = device.capabilities.writableSettings.toSet(),
            connectionPrompt = prompt,
        )
    } ?: SystemPresentationState(
        islandMode = mode,
        focusNotificationEnabled = settings.focusNotificationEnabled,
        allowIslandFields = allowIslandFields,
    )
    return copy(presentation = state)
}

internal fun SystemPresentationState.snapshot() = copy(actions = actions.toSet())

internal fun UserSettings.allowsConnectionPrompt(): Boolean = when (islandMode) {
    IslandMode.OFF -> false
    IslandMode.OFFICIAL -> true
    IslandMode.MODULE -> IslandTrigger.CONNECTION in islandTriggers
}

private fun Map<DeviceField, FieldReading>.reading(field: DeviceField): FieldReading = this[field] ?: FieldReading.Loading

private fun FieldReading.normalizedBattery(): PresentationReading<Int> = when (this) {
    FieldReading.Loading -> PresentationReading.Loading
    is FieldReading.NotProvided -> PresentationReading.NotProvided
    is FieldReading.Unknown -> PresentationReading.Unknown
    is FieldReading.Known -> (value as? FieldValue.Number)?.value
        ?.takeIf { it in 0..100 }
        ?.let { PresentationReading.Known(it) }
        ?: PresentationReading.Unknown
}

private fun FieldReading.normalizedNumber(): PresentationReading<Int> = when (this) {
    FieldReading.Loading -> PresentationReading.Loading
    is FieldReading.NotProvided -> PresentationReading.NotProvided
    is FieldReading.Unknown -> PresentationReading.Unknown
    is FieldReading.Known -> (value as? FieldValue.Number)?.value
        ?.let { PresentationReading.Known(it) }
        ?: PresentationReading.Unknown
}

private fun FieldReading.normalizedText(): PresentationReading<String> = when (this) {
    FieldReading.Loading -> PresentationReading.Loading
    is FieldReading.NotProvided -> PresentationReading.NotProvided
    is FieldReading.Unknown -> PresentationReading.Unknown
    is FieldReading.Known -> (value as? FieldValue.Text)?.value
        ?.let { PresentationReading.Known(it) }
        ?: PresentationReading.Unknown
}

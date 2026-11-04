package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.IslandMode
import io.github.dohex.openrose.core.IslandTrigger
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.UserSettings
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

/** The system view of one ControlState capture, showing [prompt] only while it belongs to that capture's ready session. */
internal fun ControlState.systemPresentation(prompt: ConnectionPrompt?): SystemPresentationState {
    val reference = readySession
    val mode = settings.islandMode
    val allowIslandFields = mode != IslandMode.OFF &&
        (mode != IslandMode.MODULE || IslandTrigger.CONNECTION in settings.islandTriggers)
    val device = device?.takeIf { reference != null }
        ?: return SystemPresentationState(
            islandMode = mode,
            focusNotificationEnabled = settings.focusNotificationEnabled,
            allowIslandFields = allowIslandFields,
        )
    return SystemPresentationState(
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
        connectionPrompt = prompt?.takeIf { it.reference == reference && it.appliesTo(settings) },
    )
}

internal fun SystemPresentationState.detached() = copy(actions = actions.toSet())

/** Whether this prompt may still show under [settings]: its mode is the saved one and that mode prompts. */
internal fun ConnectionPrompt.appliesTo(settings: UserSettings): Boolean = mode == settings.islandMode && settings.allowsConnectionPrompt()

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

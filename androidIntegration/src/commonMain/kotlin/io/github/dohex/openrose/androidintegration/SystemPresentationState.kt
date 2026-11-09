package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.SessionReference
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

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

/** A short connection or in-case event which must be claimed before an adapter triggers SystemUI. */
data class IslandPrompt(
    val id: String,
    val trigger: IslandTrigger,
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
    val statusNotificationTarget: StatusNotificationTarget = StatusNotificationTarget.OPENROSE,
    /** 动态取色: the status notification icon takes the system wallpaper colors where the system provides them. */
    val dynamicColor: Boolean = false,
    val battery: SystemBatteryState? = null,
    val noise: SystemNoiseState? = null,
    val actions: Set<DeviceSetting> = emptySet(),
    val prompt: IslandPrompt? = null,
) {
    val showStatusNotification: Boolean
        get() = reference != null && focusNotificationEnabled

    /**
     * Whether the status notification carries the persistent summary state. Official mode leaves it out: the connection
     * prompt is its only island, as with Xiaomi's own earphones, so the two never share the island at connection.
     */
    val statusSummaryIsland: Boolean
        get() = allowIslandFields && islandMode == IslandMode.MODULE
}

/** The system view of one ControlState capture, showing [prompt] only while it belongs to that capture's ready session. */
internal fun ControlState.systemPresentation(settings: IntegrationSettings, prompt: IslandPrompt?): SystemPresentationState {
    val reference = readySession
    val mode = settings.islandMode
    val allowIslandFields = mode != IslandMode.OFF && (mode != IslandMode.MODULE || settings.islandTriggers.isNotEmpty())
    val device = device?.takeIf { reference != null }
        ?: return SystemPresentationState(
            islandMode = mode,
            focusNotificationEnabled = settings.focusNotificationEnabled,
            allowIslandFields = allowIslandFields,
            statusNotificationTarget = settings.statusNotificationTarget,
            dynamicColor = settings.dynamicColor,
        )
    return SystemPresentationState(
        reference = reference,
        notificationTag = device.deviceId.value,
        islandMode = mode,
        focusNotificationEnabled = settings.focusNotificationEnabled,
        allowIslandFields = allowIslandFields,
        statusNotificationTarget = settings.statusNotificationTarget,
        dynamicColor = settings.dynamicColor,
        battery = SystemBatteryState(
            left = device.fields.reading(DeviceField.LEFT_BATTERY).normalizedBattery(),
            right = device.fields.reading(DeviceField.RIGHT_BATTERY).normalizedBattery(),
            case = if (device.profile.batteryLayout == BatteryLayout.EARBUDS_ONLY) {
                PresentationReading.NotProvided
            } else {
                device.fields.reading(DeviceField.CASE_BATTERY).normalizedBattery()
            },
        ).takeIf { device.publishesSystemBattery },
        noise = SystemNoiseState(
            mode = device.fields.reading(DeviceField.NOISE_MODE).normalizedText(),
            ancLevel = device.fields.reading(DeviceField.ANC_LEVEL).normalizedNumber(),
            transparencyLevel = device.fields.reading(DeviceField.TRANSPARENCY_LEVEL).normalizedNumber(),
        ),
        actions = device.capabilities.writableSettings.toSet(),
        prompt = prompt?.takeIf { it.reference == reference && it.appliesTo(settings) },
    )
}

internal fun SystemPresentationState.detached() = copy(actions = actions.toSet())

/** Whether this prompt may still show under [settings]: its mode is the saved one and that mode prompts on its trigger. */
internal fun IslandPrompt.appliesTo(settings: IntegrationSettings): Boolean = mode == settings.islandMode &&
    settings.allowsPrompt(trigger)

/** Official mode prompts only on connection; module mode prompts on the display timings the user selected. */
internal fun IntegrationSettings.allowsPrompt(trigger: IslandTrigger): Boolean = when (islandMode) {
    IslandMode.OFF -> false
    IslandMode.OFFICIAL -> trigger == IslandTrigger.CONNECTION
    IslandMode.MODULE -> trigger in islandTriggers
}

/** Whether this model can report an in-case event: it has a charging case and its protocol carries the in-case flags. */
val ModelProfile.reportsInCase: Boolean
    get() = batteryLayout == BatteryLayout.EARBUDS_AND_CASE && DeviceField.LEFT_IN_CASE in family.readableFields

/** Both earbuds' reported in-case flags, or null when the model cannot report them; a null side is unknown or FF. */
internal fun DeviceState.inCaseFlags(): Pair<Boolean?, Boolean?>? {
    if (!profile.reportsInCase) return null
    fun flag(field: DeviceField) = ((fields[field] as? FieldReading.Known)?.value as? FieldValue.Toggle)?.value
    return flag(DeviceField.LEFT_IN_CASE) to flag(DeviceField.RIGHT_IN_CASE)
}

/**
 * System surfaces (MiLink, the island, the status notification) render earbud and case batteries. A single unit has no
 * evidenced form there yet, so its battery is not published to them.
 */
internal val DeviceState.publishesSystemBattery: Boolean
    get() = profile.batteryLayout != BatteryLayout.SINGLE_UNIT

/** The device fields system surfaces may see. */
internal fun DeviceState.systemFields(): Map<DeviceField, FieldReading> = when (profile.batteryLayout) {
    BatteryLayout.EARBUDS_AND_CASE -> fields
    BatteryLayout.EARBUDS_ONLY -> fields.filterKeys { it != DeviceField.CASE_BATTERY && it != DeviceField.CASE_FLAG }
    BatteryLayout.SINGLE_UNIT -> fields.filterKeys { it !in batteryStateFields }
}

private val batteryStateFields = setOf(
    DeviceField.LEFT_BATTERY,
    DeviceField.RIGHT_BATTERY,
    DeviceField.CASE_BATTERY,
    DeviceField.LEFT_CHARGING,
    DeviceField.RIGHT_CHARGING,
    DeviceField.LEFT_IN_CASE,
    DeviceField.RIGHT_IN_CASE,
    DeviceField.CASE_FLAG,
)

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

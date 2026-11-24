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

data class IslandPrompt(
    val id: String,
    val trigger: IslandTrigger,
    val mode: IslandMode,
    val reference: SessionReference,
    val claimed: Boolean = false,
    val durationMillis: Long,
)

data class SystemPresentationState(
    val reference: SessionReference? = null,
    val notificationTag: String? = null,
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    val islandSummaryEnabled: Boolean = true,
    val allowIslandFields: Boolean = false,
    val statusNotificationTarget: StatusNotificationTarget = StatusNotificationTarget.OPENROSE,
    val dynamicColor: Boolean = false,
    val battery: SystemBatteryState? = null,
    val noise: SystemNoiseState? = null,
    val actions: Set<DeviceSetting> = emptySet(),
    val prompt: IslandPrompt? = null,
) {
    val showStatusNotification: Boolean
        get() = reference != null && focusNotificationEnabled

    val statusSummaryIsland: Boolean
        get() = allowIslandFields && islandMode == IslandMode.MODULE && islandSummaryEnabled
}

internal fun ControlState.systemPresentation(settings: IntegrationSettings, prompt: IslandPrompt?): SystemPresentationState {
    val reference = readySession
    val mode = settings.islandMode
    val moduleIsland = settings.islandTriggers.isNotEmpty() || (settings.islandSummaryEnabled && settings.focusNotificationEnabled)
    val allowIslandFields = mode != IslandMode.OFF && (mode != IslandMode.MODULE || moduleIsland)
    val device = device?.takeIf { reference != null }
        ?: return SystemPresentationState(
            islandMode = mode,
            focusNotificationEnabled = settings.focusNotificationEnabled,
            islandSummaryEnabled = settings.islandSummaryEnabled,
            allowIslandFields = allowIslandFields,
            statusNotificationTarget = settings.statusNotificationTarget,
            dynamicColor = settings.dynamicColor,
        )
    return SystemPresentationState(
        reference = reference,
        notificationTag = device.deviceId.value,
        islandMode = mode,
        focusNotificationEnabled = settings.focusNotificationEnabled,
        islandSummaryEnabled = settings.islandSummaryEnabled,
        allowIslandFields = allowIslandFields,
        statusNotificationTarget = settings.statusNotificationTarget,
        dynamicColor = settings.dynamicColor,
        battery = SystemBatteryState(
            left = device.presentationReading(DeviceField.LEFT_BATTERY).normalizedBattery(),
            right = device.presentationReading(DeviceField.RIGHT_BATTERY).normalizedBattery(),
            case = if (device.profile.batteryLayout == BatteryLayout.EARBUDS_ONLY) {
                PresentationReading.NotProvided
            } else {
                device.presentationReading(DeviceField.CASE_BATTERY).normalizedBattery()
            },
        ).takeIf { device.publishesSystemBattery },
        noise = SystemNoiseState(
            mode = device.presentationReading(DeviceField.NOISE_MODE).normalizedText(),
            ancLevel = device.presentationReading(DeviceField.ANC_LEVEL).normalizedNumber(),
            transparencyLevel = device.presentationReading(DeviceField.TRANSPARENCY_LEVEL).normalizedNumber(),
        ),
        actions = device.capabilities.writableSettings,
        prompt = prompt?.takeIf { it.reference == reference && it.appliesTo(settings) },
    )
}

internal fun IslandPrompt.appliesTo(settings: IntegrationSettings): Boolean = mode == settings.islandMode &&
    settings.allowsPrompt(trigger)

internal fun IntegrationSettings.allowsPrompt(trigger: IslandTrigger): Boolean = when (islandMode) {
    IslandMode.OFF -> false
    IslandMode.OFFICIAL -> trigger == IslandTrigger.CONNECTION
    IslandMode.MODULE -> trigger in islandTriggers
}

val ModelProfile.reportsInCase: Boolean
    get() = batteryLayout == BatteryLayout.EARBUDS_AND_CASE && DeviceField.LEFT_IN_CASE in capabilities().readableFields

internal fun DeviceState.inCaseFlags(): Pair<Boolean?, Boolean?>? {
    if (!profile.reportsInCase) return null
    fun flag(field: DeviceField) = confirmedToggle(field)
    return flag(DeviceField.LEFT_IN_CASE) to flag(DeviceField.RIGHT_IN_CASE)
}

internal val DeviceState.publishesSystemBattery: Boolean
    get() = profile.batteryLayout != BatteryLayout.SINGLE_UNIT

internal fun DeviceState.systemFields(): Map<DeviceField, FieldReading> = when (profile.batteryLayout) {
    BatteryLayout.EARBUDS_AND_CASE -> diagnostics.fields
    BatteryLayout.EARBUDS_ONLY -> diagnostics.fields.filterKeys { it != DeviceField.CASE_BATTERY && it != DeviceField.CASE_FLAG }
    BatteryLayout.SINGLE_UNIT -> diagnostics.fields.filterKeys { it !in batteryStateFields }
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

private fun DeviceState.presentationReading(field: DeviceField): FieldReading = reading(field) ?: FieldReading.Loading

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

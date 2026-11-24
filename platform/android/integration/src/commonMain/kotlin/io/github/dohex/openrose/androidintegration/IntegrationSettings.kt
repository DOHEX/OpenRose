package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

enum class ControlWay { ORDINARY, MODULE }
enum class IslandMode { OFF, OFFICIAL, MODULE }

enum class IslandTrigger { CONNECTION, IN_CASE }
enum class FusionSettingsDestination { SYSTEM, OPENROSE }

enum class StatusNotificationTarget { OPENROSE, SYSTEM, ROSELINK, QUICK_PANEL }

data class IntegrationSettings(
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    val islandSummaryEnabled: Boolean = true,
    val islandTriggers: PersistentSet<IslandTrigger> = persistentSetOf(IslandTrigger.CONNECTION),
    val statusNotificationTarget: StatusNotificationTarget = StatusNotificationTarget.OPENROSE,
    val fusionSettingsDestination: FusionSettingsDestination = FusionSettingsDestination.SYSTEM,
    val fusionOffAsWind: Boolean = false,
    val backgroundControlEnabled: Boolean = false,
    val controlWay: ControlWay = ControlWay.ORDINARY,
    val dynamicColor: Boolean = false,
)

object IntegrationSettingsCodec {
    const val CONTROL_WAY_KEY = "androidIntegration.control_way"

    fun encodeControlWay(way: ControlWay): String = when (way) {
        ControlWay.ORDINARY -> "ordinary"
        ControlWay.MODULE -> "module"
    }

    fun decodeControlWay(value: String?): ControlWay = when (value) {
        "ordinary" -> ControlWay.ORDINARY
        "module" -> ControlWay.MODULE
        else -> ControlWay.ORDINARY
    }

    fun encode(settings: IntegrationSettings): Map<String, String> = mapOf(
        "androidIntegration.island_mode" to when (settings.islandMode) {
            IslandMode.OFF -> "off"
            IslandMode.OFFICIAL -> "official"
            IslandMode.MODULE -> "module"
        },
        "androidIntegration.focus_notification_enabled" to settings.focusNotificationEnabled.toString(),
        "androidIntegration.island_summary_enabled" to settings.islandSummaryEnabled.toString(),
        "androidIntegration.island_triggers" to settings.islandTriggers.joinToString(",") {
            when (it) {
                IslandTrigger.CONNECTION -> "connection"
                IslandTrigger.IN_CASE -> "in_case"
            }
        },
        "androidIntegration.status_notification_target" to when (settings.statusNotificationTarget) {
            StatusNotificationTarget.OPENROSE -> "openrose"
            StatusNotificationTarget.SYSTEM -> "system"
            StatusNotificationTarget.ROSELINK -> "roselink"
            StatusNotificationTarget.QUICK_PANEL -> "quick_panel"
        },
        "androidIntegration.fusion_settings_destination" to when (settings.fusionSettingsDestination) {
            FusionSettingsDestination.SYSTEM -> "system"
            FusionSettingsDestination.OPENROSE -> "openrose"
        },
        "androidIntegration.fusion_off_as_wind" to settings.fusionOffAsWind.toString(),
        "androidIntegration.background_control_enabled" to settings.backgroundControlEnabled.toString(),
        CONTROL_WAY_KEY to encodeControlWay(settings.controlWay),
        "androidIntegration.dynamic_color" to settings.dynamicColor.toString(),
    )

    fun decode(values: Map<String, String>): IntegrationSettings {
        val defaults = IntegrationSettings()
        return IntegrationSettings(
            islandMode = decodeIslandMode(values["androidIntegration.island_mode"]) ?: defaults.islandMode,
            focusNotificationEnabled = values["androidIntegration.focus_notification_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.focusNotificationEnabled,
            islandSummaryEnabled = values["androidIntegration.island_summary_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.islandSummaryEnabled,
            islandTriggers = decodeTriggers(values["androidIntegration.island_triggers"]) ?: defaults.islandTriggers,
            statusNotificationTarget = decodeStatusTarget(values["androidIntegration.status_notification_target"])
                ?: defaults.statusNotificationTarget,
            fusionSettingsDestination = decodeFusionDestination(values["androidIntegration.fusion_settings_destination"])
                ?: defaults.fusionSettingsDestination,
            fusionOffAsWind = values["androidIntegration.fusion_off_as_wind"]?.toBooleanStrictOrNull() ?: defaults.fusionOffAsWind,
            backgroundControlEnabled = values["androidIntegration.background_control_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.backgroundControlEnabled,
            controlWay = decodeControlWay(values[CONTROL_WAY_KEY]),
            dynamicColor = values["androidIntegration.dynamic_color"]?.toBooleanStrictOrNull() ?: defaults.dynamicColor,
        )
    }

    private fun decodeIslandMode(value: String?): IslandMode? = when (value) {
        "off" -> IslandMode.OFF
        "official" -> IslandMode.OFFICIAL
        "module" -> IslandMode.MODULE
        else -> null
    }

    private fun decodeFusionDestination(value: String?): FusionSettingsDestination? = when (value?.lowercase()) {
        "system" -> FusionSettingsDestination.SYSTEM
        "openrose" -> FusionSettingsDestination.OPENROSE
        else -> null
    }

    private fun decodeStatusTarget(value: String?): StatusNotificationTarget? = when (value) {
        "openrose" -> StatusNotificationTarget.OPENROSE
        "system" -> StatusNotificationTarget.SYSTEM
        "roselink" -> StatusNotificationTarget.ROSELINK
        "quick_panel" -> StatusNotificationTarget.QUICK_PANEL
        else -> null
    }

    private fun decodeTriggers(value: String?): PersistentSet<IslandTrigger>? {
        value ?: return null
        if (value.isEmpty()) return persistentSetOf()
        return value.split(",").map { token ->
            when (token) {
                "connection" -> IslandTrigger.CONNECTION
                "in_case" -> IslandTrigger.IN_CASE
                else -> return null
            }
        }.toPersistentSet()
    }
}

data class HostSettings(
    val control: UserSettings = UserSettings(),
    val integration: IntegrationSettings = IntegrationSettings(),
)

object HostSettingsCodec {
    fun stringEntries(values: Map<String, Any?>): Map<String, String> = values.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    fun encode(settings: HostSettings): Map<String, String> = UserSettingsCodec.encode(settings.control) +
        IntegrationSettingsCodec.encode(settings.integration)

    fun decode(values: Map<String, String>): HostSettings = HostSettings(
        UserSettingsCodec.decode(values),
        IntegrationSettingsCodec.decode(values),
    )
}

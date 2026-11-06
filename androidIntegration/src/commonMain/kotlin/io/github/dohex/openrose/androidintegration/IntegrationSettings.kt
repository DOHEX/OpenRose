package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

enum class ControlWay { ORDINARY, MODULE }
enum class IslandMode { OFF, OFFICIAL, MODULE }
enum class IslandTrigger { CONNECTION }
enum class FusionSettingsDestination { SYSTEM, OPENROSE }

data class IntegrationSettings(
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    val islandTriggers: PersistentSet<IslandTrigger> = persistentSetOf(IslandTrigger.CONNECTION),
    val fusionSettingsDestination: FusionSettingsDestination = FusionSettingsDestination.SYSTEM,
    val fusionOffAsWind: Boolean = false,
    val backgroundControlEnabled: Boolean = false,
    val controlWay: ControlWay = ControlWay.ORDINARY,
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
        "androidIntegration.island_triggers" to settings.islandTriggers.joinToString(",") {
            when (it) {
                IslandTrigger.CONNECTION -> "connection"
            }
        },
        "androidIntegration.fusion_settings_destination" to when (settings.fusionSettingsDestination) {
            FusionSettingsDestination.SYSTEM -> "system"
            FusionSettingsDestination.OPENROSE -> "openrose"
        },
        "androidIntegration.fusion_off_as_wind" to settings.fusionOffAsWind.toString(),
        "androidIntegration.background_control_enabled" to settings.backgroundControlEnabled.toString(),
        CONTROL_WAY_KEY to encodeControlWay(settings.controlWay),
    )

    fun decode(values: Map<String, String>): IntegrationSettings {
        val defaults = IntegrationSettings()
        return IntegrationSettings(
            islandMode = when (values["androidIntegration.island_mode"]) {
                "off" -> IslandMode.OFF
                "official" -> IslandMode.OFFICIAL
                "module" -> IslandMode.MODULE
                else -> defaults.islandMode
            },
            focusNotificationEnabled = values["androidIntegration.focus_notification_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.focusNotificationEnabled,
            islandTriggers = when (values["androidIntegration.island_triggers"]) {
                "" -> persistentSetOf()
                "connection" -> persistentSetOf(IslandTrigger.CONNECTION)
                else -> defaults.islandTriggers
            },
            fusionSettingsDestination = when (values["androidIntegration.fusion_settings_destination"]?.lowercase()) {
                "system" -> FusionSettingsDestination.SYSTEM
                "openrose" -> FusionSettingsDestination.OPENROSE
                else -> defaults.fusionSettingsDestination
            },
            fusionOffAsWind = values["androidIntegration.fusion_off_as_wind"]?.toBooleanStrictOrNull() ?: defaults.fusionOffAsWind,
            backgroundControlEnabled = values["androidIntegration.background_control_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.backgroundControlEnabled,
            controlWay = decodeControlWay(values[CONTROL_WAY_KEY]),
        )
    }
}

data class HostSettings(
    val control: UserSettings = UserSettings(),
    val integration: IntegrationSettings = IntegrationSettings(),
)

object HostSettingsCodec {
    /** The preference entries a codec can read: raw preferences also hold other types, which are not settings. */
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

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.UserSettings
import io.github.dohex.openrose.core.UserSettingsCodec
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

enum class ControlWay { ORDINARY, MODULE }
enum class IslandMode { OFF, OFFICIAL, MODULE }

/** 显示时机: the earphone events that show a short module-mode prompt. */
enum class IslandTrigger { CONNECTION, IN_CASE }
enum class FusionSettingsDestination { SYSTEM, OPENROSE }

/** 状态通知点击目标: where tapping the status notification card or its expanded island goes. */
enum class StatusNotificationTarget { OPENROSE, SYSTEM, ROSELINK }

data class IntegrationSettings(
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    val islandTriggers: PersistentSet<IslandTrigger> = persistentSetOf(IslandTrigger.CONNECTION),
    val statusNotificationTarget: StatusNotificationTarget = StatusNotificationTarget.OPENROSE,
    val fusionSettingsDestination: FusionSettingsDestination = FusionSettingsDestination.SYSTEM,
    val fusionOffAsWind: Boolean = false,
    val backgroundControlEnabled: Boolean = false,
    val controlWay: ControlWay = ControlWay.ORDINARY,
    /** 动态取色: on Android 12+, the application's colors and the status notification icon follow the system wallpaper. */
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
            islandMode = when (values["androidIntegration.island_mode"]) {
                "off" -> IslandMode.OFF
                "official" -> IslandMode.OFFICIAL
                "module" -> IslandMode.MODULE
                else -> defaults.islandMode
            },
            focusNotificationEnabled = values["androidIntegration.focus_notification_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.focusNotificationEnabled,
            islandTriggers = decodeTriggers(values["androidIntegration.island_triggers"]) ?: defaults.islandTriggers,
            statusNotificationTarget = decodeStatusTarget(values["androidIntegration.status_notification_target"])
                ?: defaults.statusNotificationTarget,
            fusionSettingsDestination = when (values["androidIntegration.fusion_settings_destination"]?.lowercase()) {
                "system" -> FusionSettingsDestination.SYSTEM
                "openrose" -> FusionSettingsDestination.OPENROSE
                else -> defaults.fusionSettingsDestination
            },
            fusionOffAsWind = values["androidIntegration.fusion_off_as_wind"]?.toBooleanStrictOrNull() ?: defaults.fusionOffAsWind,
            backgroundControlEnabled = values["androidIntegration.background_control_enabled"]?.toBooleanStrictOrNull()
                ?: defaults.backgroundControlEnabled,
            controlWay = decodeControlWay(values[CONTROL_WAY_KEY]),
            dynamicColor = values["androidIntegration.dynamic_color"]?.toBooleanStrictOrNull() ?: defaults.dynamicColor,
        )
    }

    private fun decodeStatusTarget(value: String?): StatusNotificationTarget? = when (value) {
        "openrose" -> StatusNotificationTarget.OPENROSE
        "system" -> StatusNotificationTarget.SYSTEM
        "roselink" -> StatusNotificationTarget.ROSELINK
        else -> null
    }

    /** An explicit empty value stays empty; a missing value or any unknown entry falls back to the default. */
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

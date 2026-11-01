package io.github.dohex.openrose.core

/**
 * Storage format shared by platform-owned preferences stores. Missing values use product defaults;
 * an explicitly stored empty trigger value means no automatic triggers.
 */
object UserSettingsCodec {
    fun encode(settings: UserSettings): Map<String, String> = mapOf(
        "interface_style" to when (settings.interfaceStyle) {
            InterfaceStyle.MIUIX -> "miuix"
            InterfaceStyle.MATERIAL3 -> "material3"
        },
        "appearance" to when (settings.appearance) {
            Appearance.SYSTEM -> "system"
            Appearance.LIGHT -> "light"
            Appearance.DARK -> "dark"
        },
        "island_mode" to when (settings.islandMode) {
            IslandMode.OFF -> "off"
            IslandMode.OFFICIAL -> "official"
            IslandMode.MODULE -> "module"
        },
        "focus_notification_enabled" to settings.focusNotificationEnabled.toString(),
        "force_ble" to settings.forceBle.toString(),
        "island_triggers" to settings.islandTriggers.map {
            when (it) {
                IslandTrigger.CONNECTION -> "connection"
            }
        }.sorted().joinToString(","),
        "page_transition" to when (settings.pageTransition) {
            PageTransition.MIUIX_SPRING -> "miuix_spring"
            PageTransition.SLIDE -> "slide"
            PageTransition.FADE -> "fade"
            PageTransition.NONE -> "none"
        },
        "background_blur_enabled" to settings.backgroundBlurEnabled.toString(),
        "bottom_bar_style" to when (settings.bottomBarStyle) {
            BottomBarStyle.FIXED -> "fixed"
            BottomBarStyle.FLOATING -> "floating"
        },
        "fusion_settings_destination" to when (settings.fusionSettingsDestination) {
            FusionSettingsDestination.SYSTEM -> "system"
            FusionSettingsDestination.OPENROSE -> "openrose"
        },
    )

    fun decode(values: Map<String, String>): UserSettings {
        val defaults = UserSettings()
        return UserSettings(
            interfaceStyle = when (values["interface_style"]) {
                "miuix" -> InterfaceStyle.MIUIX
                "material3" -> InterfaceStyle.MATERIAL3
                else -> defaults.interfaceStyle
            },
            appearance = when (values["appearance"]) {
                "system" -> Appearance.SYSTEM
                "light" -> Appearance.LIGHT
                "dark" -> Appearance.DARK
                else -> defaults.appearance
            },
            islandMode = when (values["island_mode"]) {
                "off" -> IslandMode.OFF
                "official" -> IslandMode.OFFICIAL
                "module" -> IslandMode.MODULE
                else -> defaults.islandMode
            },
            focusNotificationEnabled = values["focus_notification_enabled"]
                ?.toBooleanStrictOrNull() ?: defaults.focusNotificationEnabled,
            forceBle = values["force_ble"]?.toBooleanStrictOrNull() ?: defaults.forceBle,
            islandTriggers = values["island_triggers"]?.split(',')?.mapNotNull {
                when (it) {
                    "connection" -> IslandTrigger.CONNECTION
                    else -> null
                }
            }?.toSet() ?: defaults.islandTriggers,
            pageTransition = when (values["page_transition"]) {
                "miuix_spring" -> PageTransition.MIUIX_SPRING
                "slide" -> PageTransition.SLIDE
                "fade" -> PageTransition.FADE
                "none" -> PageTransition.NONE
                else -> defaults.pageTransition
            },
            backgroundBlurEnabled = values["background_blur_enabled"]
                ?.toBooleanStrictOrNull() ?: defaults.backgroundBlurEnabled,
            bottomBarStyle = when (values["bottom_bar_style"]) {
                "fixed" -> BottomBarStyle.FIXED
                "floating" -> BottomBarStyle.FLOATING
                else -> defaults.bottomBarStyle
            },
            fusionSettingsDestination = when (values["fusion_settings_destination"]?.lowercase()) {
                "system" -> FusionSettingsDestination.SYSTEM
                "openrose" -> FusionSettingsDestination.OPENROSE
                else -> defaults.fusionSettingsDestination
            },
        )
    }
}

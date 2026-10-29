package io.github.dohex.openrose.core

/** Component style is independent from light/dark appearance. */
enum class InterfaceStyle {
    MIUIX,
    MATERIAL3,
}

enum class Appearance {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Page transition animation options, independent of component style. */
enum class PageTransition {
    MIUIX_SPRING,
    SLIDE,
    FADE,
    NONE,
}

/** Both supported island presentation modes are rendered by the system. */
enum class IslandMode {
    OFF,
    OFFICIAL,
    MODULE,
}

/** Only events with an established source belong here. */
enum class IslandTrigger {
    CONNECTION,
}

data class UserSettings(
    val interfaceStyle: InterfaceStyle = InterfaceStyle.MIUIX,
    val appearance: Appearance = Appearance.SYSTEM,
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    val islandTriggers: Set<IslandTrigger> = setOf(IslandTrigger.CONNECTION),
    val forceBle: Boolean = false,
    val pageTransition: PageTransition = PageTransition.MIUIX_SPRING,
)

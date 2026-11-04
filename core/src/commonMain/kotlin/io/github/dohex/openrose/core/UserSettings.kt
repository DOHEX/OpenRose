package io.github.dohex.openrose.core

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

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

/** Bottom navigation bar presentation style, independent of component style. */
enum class BottomBarStyle {
    FIXED,
    FLOATING,
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

/** Destination when clicking additional settings in Fusion Device Center. */
enum class FusionSettingsDestination {
    SYSTEM,
    OPENROSE,
}

/**
 * The user's chosen host for the one authoritative control session (ADR 0011). It states intent only;
 * whether that owner is ready and whether earphones are connected are separate facts.
 */
enum class ControlWay {
    /** The OpenRose application hosts the session. */
    ORDINARY,
    /** The system Bluetooth host (`com.android.bluetooth`) hosts the session through the module. */
    MODULE,
}

data class UserSettings(
    val interfaceStyle: InterfaceStyle = InterfaceStyle.MIUIX,
    val appearance: Appearance = Appearance.SYSTEM,
    val islandMode: IslandMode = IslandMode.OFFICIAL,
    val focusNotificationEnabled: Boolean = true,
    val islandTriggers: PersistentSet<IslandTrigger> = persistentSetOf(IslandTrigger.CONNECTION),
    val forceBle: Boolean = false,
    val pageTransition: PageTransition = PageTransition.MIUIX_SPRING,
    val backgroundBlurEnabled: Boolean = true,
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.FIXED,
    val fusionSettingsDestination: FusionSettingsDestination = FusionSettingsDestination.SYSTEM,
    /**
     * Fusion device center and system headset entries: a tap on "off" writes wind reduction instead of normal
     * when the model has it. Wind already reads back as off there, so the displayed selection does not change.
     */
    val fusionOffAsWind: Boolean = false,
    /** Ordinary control only: keep the control connection available while the interface is closed. Off until the user opts in. */
    val backgroundControlEnabled: Boolean = false,
    val controlWay: ControlWay = ControlWay.ORDINARY,
)

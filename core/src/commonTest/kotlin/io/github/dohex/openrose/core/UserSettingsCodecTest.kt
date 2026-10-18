package io.github.dohex.openrose.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UserSettingsCodecTest {
    @Test
    fun missingSettingsUseTheConfirmedProductDefaults() {
        assertEquals(
            UserSettings(
                interfaceStyle = InterfaceStyle.MIUIX,
                appearance = Appearance.SYSTEM,
                islandMode = IslandMode.OFFICIAL,
                focusNotificationEnabled = true,
                islandTriggers = setOf(IslandTrigger.CONNECTION),
            ),
            UserSettingsCodec.decode(emptyMap()),
        )
    }

    @Test
    fun unknownStoredValuesDoNotInventUnsupportedTriggerEvents() {
        val restored = UserSettingsCodec.decode(
            mapOf(
                "interface_style" to "future-style",
                "appearance" to "future-appearance",
                "island_mode" to "future-mode",
                "focus_notification_enabled" to "maybe",
                "island_triggers" to "wear,case-entry",
            ),
        )

        assertEquals(InterfaceStyle.MIUIX, restored.interfaceStyle)
        assertEquals(Appearance.SYSTEM, restored.appearance)
        assertEquals(IslandMode.OFFICIAL, restored.islandMode)
        assertEquals(true, restored.focusNotificationEnabled)
        assertEquals(emptySet(), restored.islandTriggers)
    }

    @Test
    fun settingsEncodeToTheDocumentedPlatformStorageFormat() {
        val stored = UserSettingsCodec.encode(
            UserSettings(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.LIGHT,
                islandMode = IslandMode.MODULE,
                focusNotificationEnabled = false,
                islandTriggers = emptySet(),
            ),
        )

        assertEquals(
            mapOf(
                "interface_style" to "material3",
                "appearance" to "light",
                "island_mode" to "module",
                "focus_notification_enabled" to "false",
                "island_triggers" to "",
            ),
            stored,
        )
    }

    @Test
    fun restoredSettingsPreserveAnExplicitlyEmptyTriggerSet() {
        val restored = UserSettingsCodec.decode(
            mapOf(
                "interface_style" to "material3",
                "appearance" to "dark",
                "island_mode" to "off",
                "focus_notification_enabled" to "false",
                "island_triggers" to "",
            ),
        )
        val session = ControlSession(
            initialPlatformState = PlatformState(permission = PermissionState.NOT_REQUIRED),
            initialSettings = restored,
        )

        assertEquals(
            UserSettings(
                interfaceStyle = InterfaceStyle.MATERIAL3,
                appearance = Appearance.DARK,
                islandMode = IslandMode.OFF,
                focusNotificationEnabled = false,
                islandTriggers = emptySet(),
            ),
            session.state.value.settings,
        )
    }
}

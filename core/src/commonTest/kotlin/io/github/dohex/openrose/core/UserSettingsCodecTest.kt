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
                "page_transition" to "future-transition",
                "bottom_bar_style" to "future-style",
            ),
        )

        assertEquals(InterfaceStyle.MIUIX, restored.interfaceStyle)
        assertEquals(Appearance.SYSTEM, restored.appearance)
        assertEquals(IslandMode.OFFICIAL, restored.islandMode)
        assertEquals(true, restored.focusNotificationEnabled)
        assertEquals(emptySet(), restored.islandTriggers)
        assertEquals(PageTransition.MIUIX_SPRING, restored.pageTransition)
        assertEquals(BottomBarStyle.FIXED, restored.bottomBarStyle)
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
                forceBle = true,
                pageTransition = PageTransition.FADE,
                backgroundBlurEnabled = false,
                bottomBarStyle = BottomBarStyle.FLOATING,
            ),
        )

        assertEquals(
            mapOf(
                "interface_style" to "material3",
                "appearance" to "light",
                "island_mode" to "module",
                "focus_notification_enabled" to "false",
                "force_ble" to "true",
                "island_triggers" to "",
                "page_transition" to "fade",
                "background_blur_enabled" to "false",
                "bottom_bar_style" to "floating",
            ),
            stored,
        )
    }

    @Test
    fun missingOrMalformedForceBleUsesTheSafeDefault() {
        assertEquals(false, UserSettingsCodec.decode(emptyMap()).forceBle)
        assertEquals(false, UserSettingsCodec.decode(mapOf("force_ble" to "maybe")).forceBle)
        assertEquals(true, UserSettingsCodec.decode(mapOf("force_ble" to "true")).forceBle)
    }

    @Test
    fun pageTransitionEncodesAndDecodesAllSupportedValues() {
        for (transition in PageTransition.entries) {
            val encoded = UserSettingsCodec.encode(UserSettings(pageTransition = transition))
            val expectedString = when (transition) {
                PageTransition.MIUIX_SPRING -> "miuix_spring"
                PageTransition.SLIDE -> "slide"
                PageTransition.FADE -> "fade"
                PageTransition.NONE -> "none"
            }
            assertEquals(expectedString, encoded["page_transition"])
            val decoded = UserSettingsCodec.decode(mapOf("page_transition" to expectedString))
            assertEquals(transition, decoded.pageTransition)
        }
    }

    @Test
    fun missingOrUnknownPageTransitionFallsBackToDefault() {
        assertEquals(PageTransition.MIUIX_SPRING, UserSettingsCodec.decode(emptyMap()).pageTransition)
        assertEquals(PageTransition.MIUIX_SPRING, UserSettingsCodec.decode(mapOf("page_transition" to "unknown")).pageTransition)
        assertEquals(PageTransition.MIUIX_SPRING, UserSettingsCodec.decode(mapOf("page_transition" to "")).pageTransition)
    }
    @Test
    fun backgroundBlurEnabledEncodesAndDecodesCorrectly() {
        val defaultSettings = UserSettingsCodec.decode(emptyMap())
        assertEquals(true, defaultSettings.backgroundBlurEnabled)

        val disabled = UserSettingsCodec.decode(mapOf("background_blur_enabled" to "false"))
        assertEquals(false, disabled.backgroundBlurEnabled)

        val invalid = UserSettingsCodec.decode(mapOf("background_blur_enabled" to "not-a-boolean"))
        assertEquals(true, invalid.backgroundBlurEnabled)

        val encoded = UserSettingsCodec.encode(UserSettings(backgroundBlurEnabled = true))
        assertEquals("true", encoded["background_blur_enabled"])
    }

    @Test
    fun bottomBarStyleEncodesAndDecodesAllSupportedValues() {
        for (style in BottomBarStyle.entries) {
            val encoded = UserSettingsCodec.encode(UserSettings(bottomBarStyle = style))
            val expectedString = when (style) {
                BottomBarStyle.FIXED -> "fixed"
                BottomBarStyle.FLOATING -> "floating"
            }
            assertEquals(expectedString, encoded["bottom_bar_style"])
            val decoded = UserSettingsCodec.decode(mapOf("bottom_bar_style" to expectedString))
            assertEquals(style, decoded.bottomBarStyle)
        }
    }

    @Test
    fun missingOrUnknownBottomBarStyleFallsBackToDefault() {
        assertEquals(BottomBarStyle.FIXED, UserSettingsCodec.decode(emptyMap()).bottomBarStyle)
        assertEquals(BottomBarStyle.FIXED, UserSettingsCodec.decode(mapOf("bottom_bar_style" to "unknown")).bottomBarStyle)
        assertEquals(BottomBarStyle.FIXED, UserSettingsCodec.decode(mapOf("bottom_bar_style" to "")).bottomBarStyle)
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

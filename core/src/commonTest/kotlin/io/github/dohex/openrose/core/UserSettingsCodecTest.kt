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

class ControlWayCodecTest {
    @Test
    fun missingOrUnknownControlWayIsOrdinaryControl() {
        assertEquals(ControlWay.ORDINARY, UserSettings().controlWay)
        assertEquals(ControlWay.ORDINARY, UserSettingsCodec.decode(emptyMap()).controlWay)
        assertEquals(ControlWay.ORDINARY, UserSettingsCodec.decode(mapOf("control_way" to "future")).controlWay)
    }

    @Test
    fun everyControlWayRoundTripsWithoutChangingOtherPreferences() {
        for (way in ControlWay.entries) {
            val settings = UserSettings(controlWay = way, forceBle = true, interfaceStyle = InterfaceStyle.MATERIAL3)
            assertEquals(settings, UserSettingsCodec.decode(UserSettingsCodec.encode(settings)))
        }
    }

    @Test
    fun preferencesStoredBeforeControlWayExistedStayOrdinary() {
        val legacy = UserSettingsCodec.encode(UserSettings(forceBle = true)) - "control_way"
        val restored = UserSettingsCodec.decode(legacy)
        assertEquals(ControlWay.ORDINARY, restored.controlWay)
        assertEquals(true, restored.forceBle)
    }
}

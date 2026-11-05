package io.github.dohex.openrose.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UiSettingsCodecTest {
    @Test
    fun roundTripPreservesAppearanceSettings() {
        val settings = UiSettings(InterfaceStyle.MATERIAL3, Appearance.DARK, PageTransition.FADE, false, BottomBarStyle.FLOATING)
        assertEquals(mapOf(
            "sharedUI.interface_style" to "material3",
            "sharedUI.appearance" to "dark",
            "sharedUI.page_transition" to "fade",
            "sharedUI.background_blur_enabled" to "false",
            "sharedUI.bottom_bar_style" to "floating",
        ), UiSettingsCodec.encode(settings))
        assertEquals(settings, UiSettingsCodec.decode(UiSettingsCodec.encode(settings)))
    }
    @Test
    fun everyAppearanceOptionRoundTrips() {
        for (style in InterfaceStyle.entries) for (appearance in Appearance.entries) {
            for (transition in PageTransition.entries) for (bar in BottomBarStyle.entries) {
                val settings = UiSettings(style, appearance, transition, false, bar)
                assertEquals(settings, UiSettingsCodec.decode(UiSettingsCodec.encode(settings)))
            }
        }
    }


    @Test
    fun missingUnknownAndLegacyValuesUseDefaults() {
        assertEquals(UiSettings(), UiSettingsCodec.decode(emptyMap()))
        assertEquals(UiSettings(), UiSettingsCodec.decode(mapOf(
            "sharedUI.interface_style" to "unknown", "sharedUI.appearance" to "unknown",
            "sharedUI.page_transition" to "unknown", "sharedUI.background_blur_enabled" to "unknown",
            "sharedUI.bottom_bar_style" to "unknown",
        )))
        assertEquals(UiSettings(), UiSettingsCodec.decode(mapOf(
            "interface_style" to "material3", "appearance" to "dark", "page_transition" to "fade",
            "background_blur_enabled" to "false", "bottom_bar_style" to "floating",
        )))
    }
}

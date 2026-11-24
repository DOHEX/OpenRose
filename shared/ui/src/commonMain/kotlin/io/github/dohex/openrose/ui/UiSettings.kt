package io.github.dohex.openrose.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import kotlin.enums.enumEntries

enum class InterfaceStyle { MIUIX, MATERIAL3 }
enum class Appearance { SYSTEM, LIGHT, DARK }
enum class PageTransition { MIUIX_SPRING, SLIDE, FADE, NONE }
enum class BottomBarStyle { FIXED, FLOATING }

enum class FloatingBarMaterial { FROSTED, LIQUID_GLASS }

@Composable
fun Appearance.isDark(): Boolean = when (this) {
    Appearance.SYSTEM -> isSystemInDarkTheme()
    Appearance.LIGHT -> false
    Appearance.DARK -> true
}

data class UiSettings(
    val interfaceStyle: InterfaceStyle = InterfaceStyle.MIUIX,
    val appearance: Appearance = Appearance.SYSTEM,
    val pageTransition: PageTransition = PageTransition.MIUIX_SPRING,
    val backgroundBlurEnabled: Boolean = true,
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.FIXED,
    val floatingBarMaterial: FloatingBarMaterial = FloatingBarMaterial.FROSTED,
)

object UiSettingsCodec {
    fun encode(settings: UiSettings): Map<String, String> = mapOf(
        "sharedUI.interface_style" to settings.interfaceStyle.name.lowercase(),
        "sharedUI.appearance" to settings.appearance.name.lowercase(),
        "sharedUI.page_transition" to settings.pageTransition.name.lowercase(),
        "sharedUI.background_blur_enabled" to settings.backgroundBlurEnabled.toString(),
        "sharedUI.bottom_bar_style" to settings.bottomBarStyle.name.lowercase(),
        "sharedUI.floating_bar_material" to settings.floatingBarMaterial.name.lowercase(),
    )

    fun decode(values: Map<String, String>): UiSettings {
        val defaults = UiSettings()
        return UiSettings(
            interfaceStyle = option(values["sharedUI.interface_style"], defaults.interfaceStyle),
            appearance = option(values["sharedUI.appearance"], defaults.appearance),
            pageTransition = option(values["sharedUI.page_transition"], defaults.pageTransition),
            backgroundBlurEnabled = values["sharedUI.background_blur_enabled"]?.toBooleanStrictOrNull() ?: defaults.backgroundBlurEnabled,
            bottomBarStyle = option(values["sharedUI.bottom_bar_style"], defaults.bottomBarStyle),
            floatingBarMaterial = option(values["sharedUI.floating_bar_material"], defaults.floatingBarMaterial),
        )
    }

    private inline fun <reified T : Enum<T>> option(value: String?, default: T): T {
        val options = enumEntries<T>()
        return options.firstOrNull { it.name.lowercase() == value } ?: default
    }
}

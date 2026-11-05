package io.github.dohex.openrose.ui

/** Component style is independent of light/dark appearance. */
enum class InterfaceStyle { MIUIX, MATERIAL3 }
enum class Appearance { SYSTEM, LIGHT, DARK }
enum class PageTransition { MIUIX_SPRING, SLIDE, FADE, NONE }
enum class BottomBarStyle { FIXED, FLOATING }

data class UiSettings(
    val interfaceStyle: InterfaceStyle = InterfaceStyle.MIUIX,
    val appearance: Appearance = Appearance.SYSTEM,
    val pageTransition: PageTransition = PageTransition.MIUIX_SPRING,
    val backgroundBlurEnabled: Boolean = true,
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.FIXED,
)

/** Layer-prefixed storage format; missing and unrecognized values use product defaults. */
object UiSettingsCodec {
    fun encode(settings: UiSettings): Map<String, String> = mapOf(
        "sharedUI.interface_style" to when (settings.interfaceStyle) {
            InterfaceStyle.MIUIX -> "miuix"
            InterfaceStyle.MATERIAL3 -> "material3"
        },
        "sharedUI.appearance" to when (settings.appearance) {
            Appearance.SYSTEM -> "system"
            Appearance.LIGHT -> "light"
            Appearance.DARK -> "dark"
        },
        "sharedUI.page_transition" to when (settings.pageTransition) {
            PageTransition.MIUIX_SPRING -> "miuix_spring"
            PageTransition.SLIDE -> "slide"
            PageTransition.FADE -> "fade"
            PageTransition.NONE -> "none"
        },
        "sharedUI.background_blur_enabled" to settings.backgroundBlurEnabled.toString(),
        "sharedUI.bottom_bar_style" to when (settings.bottomBarStyle) {
            BottomBarStyle.FIXED -> "fixed"
            BottomBarStyle.FLOATING -> "floating"
        },
    )

    fun decode(values: Map<String, String>): UiSettings {
        val defaults = UiSettings()
        return UiSettings(
            interfaceStyle = when (values["sharedUI.interface_style"]) {
                "miuix" -> InterfaceStyle.MIUIX
                "material3" -> InterfaceStyle.MATERIAL3
                else -> defaults.interfaceStyle
            },
            appearance = when (values["sharedUI.appearance"]) {
                "system" -> Appearance.SYSTEM
                "light" -> Appearance.LIGHT
                "dark" -> Appearance.DARK
                else -> defaults.appearance
            },
            pageTransition = when (values["sharedUI.page_transition"]) {
                "miuix_spring" -> PageTransition.MIUIX_SPRING
                "slide" -> PageTransition.SLIDE
                "fade" -> PageTransition.FADE
                "none" -> PageTransition.NONE
                else -> defaults.pageTransition
            },
            backgroundBlurEnabled = values["sharedUI.background_blur_enabled"]?.toBooleanStrictOrNull() ?: defaults.backgroundBlurEnabled,
            bottomBarStyle = when (values["sharedUI.bottom_bar_style"]) {
                "fixed" -> BottomBarStyle.FIXED
                "floating" -> BottomBarStyle.FLOATING
                else -> defaults.bottomBarStyle
            },
        )
    }
}

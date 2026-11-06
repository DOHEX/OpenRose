package io.github.dohex.openrose.desktop

/** Desktop-only preferences. Login launch is owned by the system and read through [LoginLaunch]. */
internal data class DesktopSettings(
    /** Closing the window keeps the control connection in the tray, when the tray is available. */
    val backgroundControl: Boolean = true,
)

/** Layer-prefixed storage format; missing and unrecognized values use product defaults. */
internal object DesktopSettingsCodec {
    private const val BACKGROUND_CONTROL = "desktop.background_control"

    fun encode(settings: DesktopSettings): Map<String, String> = mapOf(
        BACKGROUND_CONTROL to settings.backgroundControl.toString(),
    )

    fun decode(values: Map<String, String>): DesktopSettings {
        val defaults = DesktopSettings()
        return DesktopSettings(
            backgroundControl = values[BACKGROUND_CONTROL]?.toBooleanStrictOrNull() ?: defaults.backgroundControl,
        )
    }
}

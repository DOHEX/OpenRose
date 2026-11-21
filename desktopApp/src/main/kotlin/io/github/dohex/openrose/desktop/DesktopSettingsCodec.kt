package io.github.dohex.openrose.desktop

import io.github.dohex.openrose.ui.desktop.DesktopSettings

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

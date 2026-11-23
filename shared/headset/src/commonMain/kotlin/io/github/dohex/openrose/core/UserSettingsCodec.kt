package io.github.dohex.openrose.core

object UserSettingsCodec {
    fun encode(settings: UserSettings): Map<String, String> = mapOf(
        "core.force_ble" to settings.forceBle.toString(),
    )

    fun decode(values: Map<String, String>): UserSettings = UserSettings(
        forceBle = values["core.force_ble"]?.toBooleanStrictOrNull() ?: false,
    )
}

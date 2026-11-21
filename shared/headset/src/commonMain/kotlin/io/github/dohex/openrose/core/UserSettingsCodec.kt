package io.github.dohex.openrose.core

/** Core-owned keys in the platform's shared preferences store. Old unprefixed keys are ignored. */
object UserSettingsCodec {
    fun encode(settings: UserSettings): Map<String, String> = mapOf(
        "core.force_ble" to settings.forceBle.toString(),
    )

    fun decode(values: Map<String, String>): UserSettings = UserSettings(
        forceBle = values["core.force_ble"]?.toBooleanStrictOrNull() ?: false,
    )
}

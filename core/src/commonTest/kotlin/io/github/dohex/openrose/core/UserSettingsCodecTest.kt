package io.github.dohex.openrose.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UserSettingsCodecTest {
    @Test
    fun forceBleRoundTripsThroughItsOwnedStorageKey() {
        for (enabled in listOf(false, true)) {
            val settings = UserSettings(forceBle = enabled)
            assertEquals(mapOf("core.force_ble" to enabled.toString()), UserSettingsCodec.encode(settings))
            assertEquals(settings, UserSettingsCodec.decode(UserSettingsCodec.encode(settings)))
        }
    }

    @Test
    fun missingAndUnknownControlValuesUseDefaults() {
        assertEquals(UserSettings(), UserSettingsCodec.decode(emptyMap()))
        for (value in listOf("maybe", "", "TRUE")) {
            assertEquals(UserSettings(), UserSettingsCodec.decode(mapOf("core.force_ble" to value)))
        }
    }

    @Test
    fun oldAndForeignKeysCannotChangeControlPreferences() {
        assertEquals(
            UserSettings(),
            UserSettingsCodec.decode(mapOf("force_ble" to "true", "androidIntegration.force_ble" to "true")),
        )
        assertEquals(
            UserSettings(forceBle = true),
            UserSettingsCodec.decode(mapOf("core.force_ble" to "true", "force_ble" to "false")),
        )
    }
}

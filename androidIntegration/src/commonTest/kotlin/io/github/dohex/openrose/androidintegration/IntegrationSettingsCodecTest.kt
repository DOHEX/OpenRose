package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.HostWire
import io.github.dohex.openrose.androidintegration.wire.render
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.core.UserSettings
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrationSettingsCodecTest {
    @Test
    fun defaultsInvalidValuesAndLegacyKeysAreIndependent() {
        assertEquals(IntegrationSettings(), IntegrationSettingsCodec.decode(emptyMap()))
        assertEquals(IntegrationSettings(), IntegrationSettingsCodec.decode(mapOf(
            "island_mode" to "module", "control_way" to "module", "background_control_enabled" to "true",
            "androidIntegration.island_mode" to "invalid", "androidIntegration.control_way" to "invalid",
            "androidIntegration.focus_notification_enabled" to "yes", "androidIntegration.island_triggers" to "unknown",
            "androidIntegration.background_control_enabled" to "yes", "androidIntegration.fusion_off_as_wind" to "yes",
            "androidIntegration.fusion_settings_destination" to "invalid",
        )))
        assertEquals(persistentSetOf(), IntegrationSettingsCodec.decode(mapOf("androidIntegration.island_triggers" to "")).islandTriggers)
    }

    @Test
    fun eachChoiceRoundTripsWithNamespacedKeys() {
        for (way in ControlWay.entries) for (mode in IslandMode.entries) for (destination in FusionSettingsDestination.entries) {
            val settings = IntegrationSettings(mode, false, persistentSetOf(), destination, true, true, way)
            val encoded = IntegrationSettingsCodec.encode(settings)
            assertTrue(encoded.keys.all { it.startsWith("androidIntegration.") })
            assertEquals(settings, IntegrationSettingsCodec.decode(encoded))
        }
        assertEquals("connection", IntegrationSettingsCodec.encode(IntegrationSettings())["androidIntegration.island_triggers"])
    }

    @Test
    fun hostCodecCombinesOnlyControlAndIntegrationAndWirePreservesBoth() {
        val settings = HostSettings(UserSettings(forceBle = true), IntegrationSettings(
            controlWay = ControlWay.MODULE, islandMode = IslandMode.MODULE, backgroundControlEnabled = true,
        ))
        val encoded = HostSettingsCodec.encode(settings)
        assertEquals("true", encoded["core.force_ble"])
        assertEquals("module", encoded["androidIntegration.control_way"])
        assertFalse(encoded.keys.any { it.startsWith("sharedUI.") })
        assertEquals(settings, HostSettingsCodec.decode(encoded + ("sharedUI.appearance" to "dark")))
        assertEquals(settings, HostWire.decodeSettings(parseWire(HostWire.encode(settings).render())))
        assertEquals(HostSettings(), HostSettingsCodec.decode(mapOf("force_ble" to "true", "control_way" to "module")))
    }
}

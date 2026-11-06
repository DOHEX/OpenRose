package io.github.dohex.openrose.androidintegration

import kotlin.test.Test
import kotlin.test.assertEquals

class HostSettingsStringEntriesTest {
    @Test
    fun onlyStringEntriesSurviveAndKeepTheirValues() {
        val raw = mapOf<String, Any?>("a" to "x", "b" to 1, "c" to true, "d" to null, "e" to "", "f" to setOf("s"), "g" to 2L, "h" to 1.5f)
        assertEquals(mapOf("a" to "x", "e" to ""), HostSettingsCodec.stringEntries(raw))
    }

    @Test
    fun noPreferencesYieldNoEntries() {
        assertEquals(emptyMap(), HostSettingsCodec.stringEntries(emptyMap()))
    }

    @Test
    fun aMistypedSettingReadsAsItsDefault() {
        val raw = mapOf<String, Any?>(IntegrationSettingsCodec.CONTROL_WAY_KEY to 1, "androidIntegration.island_mode" to "off")
        val settings = HostSettingsCodec.decode(HostSettingsCodec.stringEntries(raw))
        assertEquals(ControlWay.ORDINARY, settings.integration.controlWay)
        assertEquals(IslandMode.OFF, settings.integration.islandMode)
    }
}

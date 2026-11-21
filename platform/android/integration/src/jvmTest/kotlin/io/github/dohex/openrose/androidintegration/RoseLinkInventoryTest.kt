package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.androidintegration.wire.Wire
import io.github.dohex.openrose.androidintegration.wire.arr
import io.github.dohex.openrose.androidintegration.wire.parseWire
import io.github.dohex.openrose.androidintegration.wire.str
import io.github.dohex.openrose.core.protocol.ModelIdentification
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.createProtocolCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoseLinkInventoryTest {
    private fun catalog(file: String) = javaClass.getResourceAsStream("/$file")!!.bufferedReader().use {
        parseWire(it.readText()).asObj().arr("models").map(Wire::asObj)
    }

    private fun identify(prefix: String): ModelProfile? {
        val bytes = prefix.chunked(2).map { it.toInt(16) }
        val company = bytes[0] or (bytes[1] shl 8)
        return ModelIdentification.identify(mapOf(company to bytes.drop(2).map(Int::toByte).toByteArray()))
    }

    @Test
    fun everyCatalogPrefixSelectsTheCorrectFamilyAndLatestFeatureQuery() {
        val covered = mutableSetOf<ModelProfile>()
        for (row in catalog("supported-devices.json")) {
            for (prefix in row.arr("device_identifiers").map(Wire::asStr)) {
                val profile = assertNotNull(identify(prefix), prefix)
                assertEquals(row.str("command_type_string").replace('-', '_'), profile.family.name.lowercase(), prefix)
                covered += profile
            }
        }
        for (row in catalog("supported-devices-3.8.6.json")) {
            for (prefix in row.arr("prefixes").map(Wire::asStr)) {
                if (row.str("kind") != "earphone") {
                    assertNull(identify(prefix), prefix)
                    continue
                }
                val profile = assertNotNull(identify(prefix), prefix)
                assertEquals(row.str("protocol_family"), profile.family.name.lowercase(), prefix)
                if (profile.family == ProtocolFamily.RS_COMMON_V2) {
                    val fields = row.arr("get_info_query_ids").map { it.asStr().toInt(16) }
                    val batch = createProtocolCodec(profile).initQueries(512).first().bytes
                    assertEquals(listOf(0xfa) + fields, batch.drop(3).dropLast(2).map { it.toInt() and 255 }, prefix)
                }
                covered += profile
            }
        }
        assertEquals(ModelProfile.entries.toSet(), covered)
    }
}

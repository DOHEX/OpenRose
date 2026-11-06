package io.github.dohex.openrose.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The codecs implement what docs/protocols records from the official app; these facts must not drift apart.
 * The research is static analysis (`hardware_validated: false`), so agreement here is not device proof.
 */
class ProtocolResearchConsistencyTest {
    private val docs = File(requireNotNull(System.getProperty("openrose.protocolDocs")) { "openrose.protocolDocs is not set" })
    private val i5 = read("models/earfeel-i5-protocol.json")
    private val i7 = read("models/earfeel-i7-protocol.json")
    private val officialModels = read("supported-devices.json").array("models").associateBy { it.string("enum_name") }

    @Test
    fun manufacturerPrefixesMatchTheResearchAndTheOfficialModelList() {
        assertEquals(i5.obj("transport").string("manufacturer_prefix"), ModelProfile.I5_ZT.manufacturerPrefix)
        assertEquals(i7.obj("transport").string("manufacturer_prefix"), ModelProfile.I7_RS_V2.manufacturerPrefix)
        assertContains(officialIdentifiers("EARFREE_I5"), ModelProfile.I5_ZT.manufacturerPrefix)
        assertContains(officialIdentifiers("EARFEEL_I7"), ModelProfile.I7_RS_V2.manufacturerPrefix)
    }

    @Test
    fun gattUuidsMatchTheResearch() {
        val i5Transport = i5.obj("transport")
        assertEquals(
            GattProfile(i5Transport.string("service"), i5Transport.string("write"), i5Transport.string("notify")),
            ModelProfile.I5_ZT.gatt,
        )
        val i7Ble = i7.obj("transport").obj("ble")
        assertEquals(GattProfile(i7Ble.string("service"), i7Ble.string("write"), i7Ble.string("notify")), ModelProfile.I7_RS_V2.gatt)
    }

    @Test
    fun i5InitialQueriesAreTheOfficialInitFramesByteForByte() {
        val commands = i5.array("fixed_commands").associateBy { it.string("name") }
        val official = i5.array("init_queries").map { name ->
            commands.getValue(name.jsonPrimitive.content).string("tx_hex").replace(" ", "")
        }
        val core = createProtocolCodec(ModelProfile.I5_ZT).initQueries(MAX_WRITE).map { it.bytes.hex() }
        assertEquals(official.sorted(), core.sorted())
    }

    @Test
    fun i7InformationQueryIsTheOfficialGetInfoFrameByteForByte() {
        val getInfo = i7.array("fixed_commands").single { it.string("name") == "getInfo" }
        assertEquals(0, getInfo.jsonObject.getValue("example_seq").jsonPrimitive.content.toInt())
        val first = createProtocolCodec(ModelProfile.I7_RS_V2).initQueries(MAX_WRITE).first()
        assertEquals(getInfo.string("example_tx_hex").replace(" ", ""), first.bytes.hex())
    }

    private fun officialIdentifiers(enumName: String): List<String> {
        val model = officialModels.getValue(enumName)
        return model.array("device_identifiers").map { it.jsonPrimitive.content }
    }

    private fun read(path: String): JsonObject = Json.parseToJsonElement(docs.resolve(path).readText()).jsonObject

    private fun JsonElement.obj(key: String): JsonObject = jsonObject.getValue(key).jsonObject

    private fun JsonElement.array(key: String): JsonArray = jsonObject.getValue(key).jsonArray

    private fun JsonElement.string(key: String): String = jsonObject.getValue(key).jsonPrimitive.content

    private companion object {
        /** Large enough that i7 sends every information type in one frame, as the official app does. */
        const val MAX_WRITE = 512
    }
}

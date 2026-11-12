package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class EqualizerProtocolTest {
    @Test
    fun i5FourPresetWritesAndReadbackMatchIndependentApkLiterals() {
        // Independent fixed TX extraction: rose_reverse/analysis/i5_fixed_sender_commands.json.
        for ((preset, tx) in listOf(
            "pop" to "08ee00000002810b000084",
            "hifi" to "08ee00000002810b000185",
            "rock" to "08ee00000002810b000286",
            "light" to "08ee00000002810b000387",
        )) {
            val setting = createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(DeviceSetting.EQ, FieldValue.Text(preset))
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("08ee00000002010a0003", setting.readback.bytes.hex())
            assertEquals(setOf("0201"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.EQ to FieldValue.Text(preset)), setting.expectedValues)
        }
    }

    @Test
    fun i5GameUsesZeroForEnabledAndItsOwnReadCommand() {
        // Independent fixed TX extraction, not the client's frame builder.
        for ((enabled, tx) in listOf(true to "08ee00000006830b00008a", false to "08ee00000006830b00018b")) {
            val setting = createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(DeviceSetting.GAME_MODE, FieldValue.Toggle(enabled))
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("08ee00000006030a0009", setting.readback.bytes.hex())
            assertEquals(setOf("0603"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.GAME_MODE to FieldValue.Toggle(enabled)), setting.expectedValues)
        }
    }

    @Test
    fun modelPresetCapabilitiesUseSourceFirmwarePartsForThe152Gate() {
        val base = setOf("pop", "hifi", "rock", "light")
        assertEquals(base, ModelProfile.I5_ZT.capabilities().equalizer!!.presets)
        assertEquals(base, ModelProfile.I7_RS_V2.capabilities().equalizer!!.presets)
        for ((parts, eap) in listOf(
            listOf(1, 5, 1) to false, listOf(1, 5, 2) to true,
            listOf(0, 15, 1) to false, listOf(0, 15, 2) to true,
            listOf(0, 16, 0) to true, listOf(1, 4, 255) to true,
            listOf(1, 5) to false, listOf(1, 5, 2, 0) to false,
            listOf(-1, 5, 2) to false, listOf(256, 5, 2) to false,
        )) {
            // The display label deliberately disagrees with source parts; it is not gate evidence.
            val firmware = FieldValue.Firmware(parts, if (eap) "0" else "999999")
            assertEquals(base + if (eap) setOf("eap") else emptySet(), ModelProfile.I7_RS_V2.capabilities(firmware).equalizer!!.presets, "$parts")
            assertEquals(base, ModelProfile.I5_ZT.capabilities(firmware).equalizer!!.presets)
        }
    }

    @Test
    fun i7PresetWritesSwapHifiPopAndUseTheEqReadback() {
        // Independent fixed TX extraction: rose_reverse/analysis/i7_sender_commands.json, seq00.
        for ((preset, tx) in listOf(
            "hifi" to "ff00022a002baa",
            "pop" to "ff00022a012caa",
            "rock" to "ff00022a022daa",
            "light" to "ff00022a032eaa",
            "eap" to "ff00022a0631aa",
        )) {
            val setting = createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(
                DeviceSetting.EQ,
                FieldValue.Text(preset),
                FieldValue.Firmware(listOf(1, 5, 2), "152"),
            )
            assertEquals(tx, setting.writeFrames.single().hex())
            // getEq fixed body FA2A with the next session sequence01, independently worked literal.
            assertEquals("ff0102fa2a26aa", setting.readback.bytes.hex())
            assertEquals(setOf("2a"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.EQ to FieldValue.Text(preset)), setting.expectedValues)
        }
    }

    @Test
    fun i7GameUsesOneForEnabledAndAClientSingleFieldReadback() {
        for ((enabled, tx) in listOf(true to "ff00020e0110aa", false to "ff00020e000faa")) {
            val setting = createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.GAME_MODE, FieldValue.Toggle(enabled))
            assertEquals(tx, setting.writeFrames.single().hex())
            // FA0E is a client inference from observed single-ID FA queries and bulk getInfo's 0E.
            assertEquals("ff0102fa0e0aaa", setting.readback.bytes.hex())
            assertEquals(setOf("0e"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.GAME_MODE to FieldValue.Toggle(enabled)), setting.expectedValues)
        }
    }

    @Test
    fun invalidTypesAndUnavailablePresetsAreRejectedBeforeAllocatingSequence() {
        for (profile in ModelProfile.entries.filter { it == ModelProfile.I5_ZT || it.family == ProtocolFamily.RS_COMMON_V2 }) {
            val codec = createProtocolCodec(profile)
            for ((kind, value) in listOf(
                DeviceSetting.EQ to FieldValue.Number(0),
                DeviceSetting.EQ to FieldValue.IntegerList(List(10) { 0 }),
                DeviceSetting.GAME_MODE to FieldValue.Number(1),
                DeviceSetting.GAME_MODE to FieldValue.Text("true"),
            )) {
                assertFailsWith<IllegalArgumentException>("$profile $kind $value") { codec.prepareSetting(kind, value) }
            }
            for (preset in listOf("custom", "custom_slot", "game1", "bass", "POP", "eap")) {
                assertFailsWith<UnsupportedOperationException>("$profile $preset") { codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text(preset)) }
            }
            for (firmware in listOf(
                FieldValue.Firmware(listOf(1, 5, 1), "999999"),
                FieldValue.Firmware(emptyList(), "152"),
                FieldValue.Firmware(listOf(1, 5, 2, 0), "152"),
                FieldValue.Firmware(listOf(-1, 5, 2), "152"),
            )) {
                assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("eap"), firmware) }
            }
            if (profile == ModelProfile.I5_ZT) {
                assertFailsWith<UnsupportedOperationException> {
                    codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("eap"), FieldValue.Firmware(listOf(1, 5, 2), "152"))
                }
            } else if (DeviceSetting.EQ in profile.capabilities().writableSettings) {
                assertEquals("ff00022a002baa", codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("hifi")).writeFrames.single().hex())
            } else {
                assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("hifi")) }
                assertEquals("ff00020e0110aa", codec.prepareSetting(DeviceSetting.GAME_MODE, FieldValue.Toggle(true)).writeFrames.single().hex())
            }
        }
    }

    @Test
    fun eqAndGameContinueTheSameI7InitializationAndAncSequence() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        codec.initQueries(20) // Four complete queries use sequences00..03.
        val eq = codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("hifi"))
        assertEquals("ff04022a002faa", eq.writeFrames.single().hex())
        assertEquals("ff0502fa2a2aaa", eq.readback.bytes.hex())
        val game = codec.prepareSetting(DeviceSetting.GAME_MODE, FieldValue.Toggle(true))
        assertEquals("ff06020e0116aa", game.writeFrames.single().hex())
        assertEquals("ff0702fa0e10aa", game.readback.bytes.hex())
        val anc = codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))
        assertEquals("ff0802090113aa", anc.writeFrames.single().hex())
        assertEquals("ff0902fa090daa", anc.readback.bytes.hex())
    }

    @Test
    fun syntheticI5ReadbacksUseLowNibblesAndNeverInterpretWriteEchoesAsState() {
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        // Deliberately synthetic offsets and high-nibble sentinels; no RX framing claim.
        for ((rx, preset) in listOf(
            "000000000002010000a0" to "pop",
            "000000000002010000a1" to "hifi",
            "000000000002010000a2" to "rock",
            "000000000002010000a3" to "light",
        )) {
            val reply = codec.feedNotification(rx.bytes()).single()
            assertEquals(setOf("0201"), reply.responseKeys)
            assertEquals(FieldValue.Text(preset), assertIs<FieldReading.Known>(reply.readings[DeviceField.EQ]).value)
        }
        for ((rx, enabled) in listOf("000000000006030000a0" to true, "000000000006030000a1" to false)) {
            val reply = codec.feedNotification(rx.bytes()).single()
            assertEquals(FieldValue.Toggle(enabled), assertIs<FieldReading.Known>(reply.readings[DeviceField.GAME_MODE]).value)
        }
        assertIs<FieldReading.Unknown>(codec.feedNotification("000000000006030000a2".bytes()).single().readings[DeviceField.GAME_MODE])
        for (echo in listOf("08ee00000002810b000084", "08ee00000006830b00008a")) {
            val reply = codec.feedNotification(echo.bytes()).single()
            assertFalse(reply.accepted)
            assertFalse(DeviceField.EQ in reply.readings || DeviceField.GAME_MODE in reply.readings)
        }
    }

    @Test
    fun actualI5InitializationSamplesCoverHifiAndGameOff() {
        // Actual Linux initialization reads, 2026-10-18, firmware125; these are not setting captures.
        // rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:8,10.
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        val eq = codec.feedNotification("09ff00000102010b000118".bytes()).single()
        val game = codec.feedNotification("09ff00000106030b00011e".bytes()).single()
        assertEquals(FieldValue.Text("hifi"), assertIs<FieldReading.Known>(eq.readings[DeviceField.EQ]).value)
        assertEquals(FieldValue.Toggle(false), assertIs<FieldReading.Known>(game.readings[DeviceField.GAME_MODE]).value)
    }

    @Test
    fun syntheticI7ReadbacksPreserveEqFormatsAndGamePolarity() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        // Whole-prefix checksum synthetic RX examples, hand-worked independently of the codec.
        for ((rx, preset) in listOf(
            "dd00022a0009aa" to "hifi",
            "dd00022a010aaa" to "pop",
            "dd00022a020baa" to "rock",
            "dd00022a030caa" to "light",
            "dd00022a060faa" to "eap",
            "dd00032a00000aaa" to "pop",
            "dd00032a01000baa" to "hifi",
        )) {
            val reply = codec.feedNotification(rx.bytes()).single()
            assertEquals(setOf("2a"), reply.responseKeys)
            assertEquals(FieldValue.Text(preset), assertIs<FieldReading.Known>(reply.readings[DeviceField.EQ]).value)
        }
        for ((rx, enabled) in listOf("dd00020e01eeaa" to true, "dd00020e00edaa" to false)) {
            val reply = codec.feedNotification(rx.bytes()).single()
            assertEquals(FieldValue.Toggle(enabled), assertIs<FieldReading.Known>(reply.readings[DeviceField.GAME_MODE]).value)
        }
        assertIs<FieldReading.Unknown>(codec.feedNotification("dd00020e02efaa".bytes()).single().readings[DeviceField.GAME_MODE])
        for ((rx, text, eap) in listOf(
            Triple("dd00040d010501f5aa", "151", false),
            Triple("dd00040d010502f6aa", "152", true),
            Triple("dd00040d001000feaa", "0160", true),
        )) {
            val reply = codec.feedNotification(rx.bytes()).single()
            val firmware = assertIs<FieldValue.Firmware>(assertIs<FieldReading.Known>(reply.readings[DeviceField.FIRMWARE]).value)
            assertEquals(text, firmware.displayText)
            assertEquals(eap, "eap" in ModelProfile.I7_RS_V2.capabilities(firmware).equalizer?.presets.orEmpty())
        }
    }
}

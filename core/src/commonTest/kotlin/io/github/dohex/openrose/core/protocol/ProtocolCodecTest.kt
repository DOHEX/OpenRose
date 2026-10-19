package io.github.dohex.openrose.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProtocolCodecTest {
    @Test
    fun fragmentedUnknownPayloadCannotBecomeAnInnerKnownFrame() {
        // Synthetic independent example: type99 contains a complete DD/09 frame as opaque V.
        val whole = createProtocolCodec(ModelProfile.I7_RS_V2)
            .feedNotification("dd000899dd00020901e9aafaaa".bytes())
        val split = createProtocolCodec(ModelProfile.I7_RS_V2)
        val first = split.feedNotification("dd000899dd00020901e9aa".bytes())
        val rest = split.feedNotification("faaa".bytes())

        assertTrue(first.isEmpty())
        assertEquals(whole, first + rest)
        assertTrue((first + rest).none { it.accepted })
        assertTrue((first + rest).none { DeviceField.NOISE_MODE in it.readings })
        val frame = "dd000899dd00020901e9aafaaa".bytes()
        for (cut in 1 until frame.size) {
            val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
            assertEquals(whole, codec.feedNotification(frame.copyOfRange(0, cut)) +
                codec.feedNotification(frame.copyOfRange(cut, frame.size)), "cut=$cut")
        }
    }

    @Test
    fun manufacturerEvidenceIdentifiesTheConfigurationWithoutAName() {
        assertEquals(ModelProfile.I5_ZT, ModelIdentification.identify(mapOf(0x5352 to hex("0001000199"))))
        assertEquals(ModelProfile.I7_RS_V2, ModelIdentification.identify(mapOf(0xe5b8 to hex("290f0100"))))
        assertNull(ModelIdentification.identify(mapOf(0x5352 to hex("00010002"))))
        assertEquals("01000200bbaa", ModelIdentification.normalizedHex(mapOf(2 to hex("aa"), 1 to hex("bb"))))
    }

    @Test
    fun i5InitialReadMatchesIndependentSenderAndActualBatteryCapture() {
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        val queries = codec.initQueries(20)
        // Independent APK literal extraction: analysis/i5_fixed_sender_commands.json.
        assertEquals(listOf(
            "08ee00000001010a0002", "08ee00000006020a0008", "08ee00000002010a0003",
            "08ee00000006030a0009", "08ee00000008010a0009", "08ee0000000d010a000e",
            "08ee00000004010a0005", "08ee0000000a010a000b", "08ee00000006050a000b",
            "08ee0000000c010a000d", "08ee00000006070a000d", "08ee00000006040a000a",
            "08ee0000000e030a0011",
        ), queries.map { it.bytes.toHex() })
        // Actual Linux sample, 2026-10-18, firmware 125. Case FF is missing, not zero.
        val reply = codec.feedNotification(hex("09ff00000101011100000164620000ffe2")).single()
        assertEquals(setOf("0101"), reply.responseKeys)
        assertEquals(FieldValue.Number(100), assertIs<FieldReading.Known>(reply.readings[DeviceField.LEFT_BATTERY]).value)
        assertIs<FieldReading.NotProvided>(reply.readings[DeviceField.CASE_BATTERY])
        assertEquals("ff", reply.readings.getValue(DeviceField.CASE_BATTERY).rawHex)
    }

    @Test
    fun i5ActualReadSamplesKeepTheirOwnFieldSemantics() {
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        val samples = listOf(
            Triple("09ff00000106020e000000010020", DeviceField.NOISE_MODE, FieldValue.Text("wind")),
            Triple("09ff00000102010b000118", DeviceField.EQ, FieldValue.Text("hifi")),
            Triple("09ff00000106030b00011e", DeviceField.GAME_MODE, FieldValue.Toggle(false)),
            Triple("09ff00000108010b00011e", DeviceField.AUDIO_MODE, FieldValue.Text("ldac")),
            Triple("09ff0000010d010e00010205002d", DeviceField.FIRMWARE, FieldValue.Firmware(listOf(1, 2, 5), "125")),
            Triple("09ff0000010a010b00001f", DeviceField.TOUCH_ENABLED, FieldValue.Toggle(true)),
            Triple("09ff0000010c010b000122", DeviceField.EAR_DETECTION, FieldValue.Toggle(false)),
            Triple("09ff00000106070b000223", DeviceField.ANC_LEVEL, FieldValue.Number(5)),
            Triple("09ff00000106040b00001e", DeviceField.TRANSPARENCY_LEVEL, FieldValue.Number(1)),
            Triple("09ff0000010e030b000126", DeviceField.PROMPT_VOLUME, FieldValue.Number(2)),
        )
        samples.forEach { (raw, field, expected) ->
            val reply = codec.feedNotification(hex(raw)).single()
            assertEquals(expected, assertIs<FieldReading.Known>(reply.readings[field]).value)
        }
        val keys = codec.feedNotification(hex("09ff00000104011200060502040605030746")).single()
        val bindings = assertIs<FieldValue.TouchKeys>(assertIs<FieldReading.Known>(keys.readings[DeviceField.TOUCH_KEYS]).value).bindings
        assertEquals("none", bindings.first().action)
        assertEquals("noise_cycle", bindings.last().action)
        val cycle = codec.feedNotification(hex("09ff00000106050e000101010126")).single()
        assertEquals(FieldValue.NoiseCycle(mapOf("normal" to true, "transparency" to true, "wind" to true, "anc" to true)),
            assertIs<FieldReading.Known>(cycle.readings[DeviceField.NOISE_CYCLE]).value)
    }

    @Test
    fun i5UnknownAndTruncatedNotificationsCannotInventAStateOrAnRxChecksum() {
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        assertFalse(codec.feedNotification(hex("09ff0000010101")).single().accepted)
        val unknown = codec.feedNotification(hex("000000000006030000a2")).single()
        assertIs<FieldReading.Unknown>(unknown.readings[DeviceField.GAME_MODE])
        assertFalse(unknown.accepted)
        // Synthetic high nibble and arbitrary RX header are intentionally accepted at this offset seam.
        val recognized = codec.feedNotification(hex("000000000006030000a0")).single()
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(recognized.readings[DeviceField.GAME_MODE]).value)
        assertFalse(codec.feedNotification(hex("0000000000ffff")).single().accepted)
        assertFalse(codec.feedNotification(hex("00000000000605000002020202")).single().accepted)
        assertFalse(codec.feedNotification(hex("000000000004010000ffffffffffffffff")).single().accepted)
    }

    @Test
    fun i7InitialQueryUsesIndependentLiteralAndCompleteSmallMtuQueries() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        val initial = codec.initQueries(512)
        assertEquals("ff001efa010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649e9aa", initial.first().bytes.toHex())
        assertEquals("ff0102fa3e3aaa", initial.last().bytes.toHex()) // Explicit client supplement.
        codec.reset()
        val small = codec.initQueries(20)
        assertEquals(4, small.size)
        assertTrue(small.all { it.bytes.size <= 20 })
        assertEquals(listOf(0, 1, 2, 3), small.map { it.bytes[1].toInt() })
        assertEquals("fa", small[2].bytes.copyOfRange(3, 4).toHex())
        assertTrue(small[2].responseKeys.isEmpty()) // Shared-only ID49 must not wait for supported state.
        assertFailsWith<IllegalArgumentException> { codec.initQueries(6) }
    }

    @Test
    fun i7SyntheticStreamKeepsDelimitersAndAcceptsOnlyDocumentedChecksumFamilies() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        val sample = hex("dd000599aadd010003aa") // Synthetic unknown record, embedded AA/DD.
        assertTrue(codec.feedNotification(sample.copyOfRange(0, 5)).isEmpty())
        assertTrue(codec.feedNotification(sample.copyOfRange(5, 8)).isEmpty())
        val unknown = codec.feedNotification(sample.copyOfRange(8, sample.size)).single()
        assertEquals(sample.toHex(), unknown.rawHex)
        assertFalse(unknown.accepted)
        val batch = codec.feedNotification(hex("dd00020901020e01faaadd00020901020e011daa"))
        assertEquals(2, batch.size)
        batch.forEach { reply ->
            assertEquals(setOf("09", "0e"), reply.responseKeys)
            assertEquals(FieldValue.Text("anc"), assertIs<FieldReading.Known>(reply.readings[DeviceField.NOISE_MODE]).value)
            assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(reply.readings[DeviceField.GAME_MODE]).value)
        }
        codec.reset()
        assertTrue(codec.feedNotification(hex("dd00020901020e0100aa")).none { it.accepted })
        codec.reset()
        assertTrue(codec.feedNotification(hex("ff00020901e9aa")).none { it.accepted })
    }

    @Test
    fun i7SyntheticReadingsPreserveBatteryFlagsPolarityAndProtocolSpecificValues() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        fun read(type: Int, value: String) = codec.feedNotification(syntheticI7(type, hex(value))).single()
        val battery = read(0x0c, "e43250")
        assertEquals(FieldValue.Number(100), assertIs<FieldReading.Known>(battery.readings[DeviceField.LEFT_BATTERY]).value)
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(battery.readings[DeviceField.LEFT_IN_CASE]).value)
        assertEquals(FieldValue.Number(80), assertIs<FieldReading.Known>(battery.readings[DeviceField.CASE_BATTERY]).value)
        assertIs<FieldReading.Unknown>(read(0x0c, "ff3250").readings[DeviceField.CASE_BATTERY])
        assertIs<FieldReading.Unknown>(read(0x0c, "ff3250").readings[DeviceField.LEFT_IN_CASE])
        assertFalse(read(0x0c, "ffffff").accepted)
        assertEquals(FieldValue.Firmware(listOf(1, 5, 2), "152"), assertIs<FieldReading.Known>(read(0x0d, "010502").readings[DeviceField.FIRMWARE]).value)
        val samples = listOf(
            Triple(0x07, "00", DeviceField.TOUCH_ENABLED to FieldValue.Toggle(true)),
            Triple(0x08, "01", DeviceField.EAR_DETECTION to FieldValue.Toggle(false)),
            Triple(0x0e, "01", DeviceField.GAME_MODE to FieldValue.Toggle(true)),
            Triple(0x32, "01", DeviceField.MULTIPOINT to FieldValue.Toggle(true)),
            Triple(0x2a, "00", DeviceField.EQ to FieldValue.Text("hifi")),
            Triple(0x2a, "0000", DeviceField.EQ to FieldValue.Text("pop")),
            Triple(0x2b, "03", DeviceField.AUDIO_MODE to FieldValue.Text("lc3")),
            Triple(0x2c, "03", DeviceField.ANC_LEVEL to FieldValue.Number(3)),
            Triple(0x2d, "05", DeviceField.TRANSPARENCY_LEVEL to FieldValue.Number(5)),
            Triple(0x2e, "00", DeviceField.PROMPT_VOLUME to FieldValue.Number(0)),
            Triple(0x45, "02", DeviceField.GAIN to FieldValue.Number(2)),
            Triple(0x46, "06", DeviceField.HEARING_PROTECTION to FieldValue.Number(6)),
        )
        samples.forEach { (type, raw, expectation) ->
            assertEquals(expectation.second, assertIs<FieldReading.Known>(read(type, raw).readings[expectation.first]).value)
        }
        val curve = read(0x3e, "86838100010203040506")
        assertEquals(FieldValue.IntegerList(listOf(-6, -3, -1, 0, 1, 2, 3, 4, 5, 6)), assertIs<FieldReading.Known>(curve.readings[DeviceField.CUSTOM_EQ]).value)
        val keys = read(1, "01080201030304041105120613071400")
        val bindings = assertIs<FieldValue.TouchKeys>(assertIs<FieldReading.Known>(keys.readings[DeviceField.TOUCH_KEYS]).value).bindings
        assertEquals("noise_cycle", bindings.first().action)
        assertEquals("none", bindings.last().action)
        assertEquals(FieldValue.NoiseCycle(mapOf("anc" to true, "wind" to false, "normal" to true, "transparency" to true)),
            assertIs<FieldReading.Known>(read(0x36, "01000101").readings[DeviceField.NOISE_CYCLE]).value)
    }

    @Test
    fun unknownOnlyOrInvalidFieldsCannotEstablishReadinessAndResetDropsFragments() {
        val i5 = createProtocolCodec(ModelProfile.I5_ZT)
        val missingBattery = i5.feedNotification(hex("09ff000001010111000001ffff0000ffe2")).single()
        assertIs<FieldReading.NotProvided>(missingBattery.readings[DeviceField.CASE_BATTERY])
        assertFalse(missingBattery.accepted)
        val i7 = createProtocolCodec(ModelProfile.I7_RS_V2)
        for ((type, value) in listOf(0xfe to "01", 0x99 to "03", 0x07 to "02", 0x07 to "0000", 0x0c to "64", 0x01 to "0108020103030404110512061307", 0x2b to "0a", 0x36 to "02020202", 0x01 to "01ff02ff03ff04ff11ff12ff13ff14ff")) {
            assertTrue(i7.feedNotification(syntheticI7(type, hex(value))).none { it.accepted })
        }
        val frame = hex("dd00020901e9aa")
        assertTrue(i7.feedNotification(frame.copyOfRange(0, 4)).isEmpty())
        i7.reset()
        assertTrue(i7.feedNotification(frame.copyOfRange(4, frame.size)).none { it.accepted })
        assertEquals(FieldValue.Text("pop"), assertIs<FieldReading.Known>(
            i7.feedNotification(hex("dd00002a012baa")).single().readings[DeviceField.EQ]).value)
    }

    @Test
    fun i7SequenceBelongsToTheCodecSessionAndWrapsAt255() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        val requests = (0..128).flatMap { codec.initQueries(512) }
        assertEquals(listOf(0, 1, 2), requests.take(3).map { it.bytes[1].toInt() and 255 })
        assertEquals(255, requests[255].bytes[1].toInt() and 255)
        assertEquals(0, requests[256].bytes[1].toInt() and 255)
        codec.reset()
        assertEquals(0, codec.initQueries(512).first().bytes[1].toInt())
    }

    @Test
    fun suppliedBatteryBytesKeepUnavailableCurrentCaseAndZeroDistinctFromFf() {
        // Source field example from independent i5 test: charging case 50; synthetic contexts below.
        val i5 = createProtocolCodec(ModelProfile.I5_ZT)
        val noCharging = i5.feedNotification(hex("000000000001010000aabb6432000050")).single()
        assertIs<FieldReading.Unknown>(noCharging.readings[DeviceField.CASE_BATTERY])
        assertEquals("50", noCharging.readings.getValue(DeviceField.CASE_BATTERY).rawHex)
        val zero = i5.feedNotification(hex("000000000001010000aabb00320000ff")).single()
        assertIs<FieldReading.Unknown>(zero.readings[DeviceField.LEFT_BATTERY])
        assertEquals("00", zero.readings.getValue(DeviceField.LEFT_BATTERY).rawHex)
        assertIs<FieldReading.NotProvided>(zero.readings[DeviceField.CASE_BATTERY])
        val i7 = createProtocolCodec(ModelProfile.I7_RS_V2)
        val outOfCase = i7.feedNotification(syntheticI7(0x0c, hex("643250"))).single()
        assertIs<FieldReading.Unknown>(outOfCase.readings[DeviceField.CASE_BATTERY])
        val allUnknown = i7.feedNotification(syntheticI7(0x0c, hex("00ffff"))).single()
        assertIs<FieldReading.Unknown>(allUnknown.readings[DeviceField.LEFT_BATTERY])
        assertIs<FieldReading.NotProvided>(allUnknown.readings[DeviceField.RIGHT_BATTERY])
        assertIs<FieldReading.Unknown>(allUnknown.readings[DeviceField.RIGHT_IN_CASE])
        assertFalse(allUnknown.accepted)
    }

    // Constructed framing around independent research field examples; never a captured RX sample.
    private fun syntheticI7(type: Int, value: ByteArray): ByteArray {
        val prefix = byteArrayOf(0xdd.toByte(), 0, (value.size + 1).toByte(), type.toByte()) + value
        return prefix + byteArrayOf(prefix.sumOf { it.toInt() and 255 }.toByte(), 0xaa.toByte())
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

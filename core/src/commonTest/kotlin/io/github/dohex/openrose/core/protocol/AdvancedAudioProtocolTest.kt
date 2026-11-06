package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class AdvancedAudioProtocolTest {
    @Test
    fun customCurveUsesTwoOrderedWritesAndTwoIndependentReadbacksAfterInitialization() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        assertEquals(4, codec.initQueries(20).size)
        val gains = mutableListOf(-6, -3, -1, 0, 1, 2, 3, 4, 5, 6)
        val setting = codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(gains))
        // APK setEqCustom fixed literal + independent dynamic curve example in
        // rose_reverse/docs/earfeel-i7-protocol.md §4.4, advanced by session seq04/05.
        assertEquals(listOf("ff04022a0433aa", "ff050b3e86838100010203040506ecaa"), setting.writeFrames.map { it.hex() })
        // APK getEq/getCustomEq fixed bodies, independently worked seq06/07 literals.
        assertEquals(listOf("ff0602fa2a2baa", "ff0702fa3e40aa"), setting.readbacks.map { it.bytes.hex() })
        assertEquals(listOf(setOf("2a"), setOf("3e")), setting.readbacks.map { it.responseKeys })
        assertEquals(listOf(setOf(DeviceField.EQ), setOf(DeviceField.CUSTOM_EQ)), setting.readbacks.map { it.expectedFields })
        assertEquals(setting.readbacks.first(), setting.readback)
        assertEquals(setting.readbacks.drop(1), setting.additionalReadbacks)
        gains[0] = 6
        assertEquals(
            mapOf(
                DeviceField.EQ to FieldValue.Text("custom"),
                DeviceField.CUSTOM_EQ to FieldValue.IntegerList(listOf(-6, -3, -1, 0, 1, 2, 3, 4, 5, 6)),
            ),
            setting.expectedValues,
        )
        assertEquals("ff08020e0118aa", codec.prepareSetting(DeviceSetting.GAME_MODE, FieldValue.Toggle(true)).writeFrames.single().hex())
    }

    @Test
    fun advancedCapabilitiesUseModelAndCompleteFirmwareBytesForTheirSeparateGates() {
        assertNull(ModelProfile.I5_ZT.capabilities().equalizer?.custom)
        val custom = ModelProfile.I7_RS_V2.capabilities().equalizer?.custom!!
        // Original i7 panel frequencies and gain limits; these are UI labels, not measured DSP data.
        assertEquals(listOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000), custom.bandsHz)
        assertEquals(-6, custom.minGain)
        assertEquals(6, custom.maxGain)
        assertEquals(emptySet(), ModelProfile.I7_RS_V2.capabilities().gainLevels)
        assertEquals(emptySet(), ModelProfile.I7_RS_V2.capabilities().hearingProtectionLevels)
        assertEquals(setOf(1, 2, 3, 4, 5), ModelProfile.I7_RS_V2.capabilities().promptVolumeLevels)
        for ((parts, gainAllowed, muteAllowed) in listOf(
            Triple(listOf(2, 1, 2), false, false), Triple(listOf(2, 1, 3), true, false),
            Triple(listOf(3, 0, 3), true, false), Triple(listOf(3, 0, 4), true, true),
            Triple(listOf(0, 21, 3), true, false), Triple(listOf(0, 30, 4), true, true),
            Triple(listOf(1, 4, 255), true, true), Triple(listOf(0, 16, 0), false, false),
            Triple(listOf(2, 1), false, false), Triple(listOf(2, 1, 3, 0), false, false),
            Triple(listOf(-1, 1, 3), false, false), Triple(listOf(256, 1, 3), false, false),
        )) {
            val firmware = FieldValue.Firmware(parts, "999999")
            val i7 = ModelProfile.I7_RS_V2.capabilities(firmware)
            val i5 = ModelProfile.I5_ZT.capabilities(firmware)
            assertEquals(if (gainAllowed) setOf(0, 1, 2) else emptySet(), i7.gainLevels, "$parts")
            assertEquals(if (gainAllowed) setOf(0, 1, 2, 3, 4, 5, 6) else emptySet(), i7.hearingProtectionLevels, "$parts")
            assertEquals(if (muteAllowed) setOf(0, 1, 2, 3, 4, 5) else setOf(1, 2, 3, 4, 5), i7.promptVolumeLevels, "$parts")
            assertEquals(emptySet(), i5.gainLevels)
            assertEquals(emptySet(), i5.hearingProtectionLevels)
            assertEquals(setOf(1, 2, 3, 4, 5), i5.promptVolumeLevels)
        }
    }

    @Test
    fun i7GainProtectionAndPromptSettingsUseTheirIndependentFirmwareGatesAndReadbacks() {
        val fw213 = FieldValue.Firmware(listOf(2, 1, 3), "213")
        val gateCodec = createProtocolCodec(ModelProfile.I7_RS_V2)
        assertFailsWith<UnsupportedOperationException> {
            gateCodec.prepareSetting(DeviceSetting.GAIN, FieldValue.Number(0))
        }
        assertFailsWith<UnsupportedOperationException> {
            gateCodec.prepareSetting(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(0))
        }
        assertFailsWith<IllegalArgumentException> {
            gateCodec.prepareSetting(DeviceSetting.GAIN, FieldValue.Number(3), fw213)
        }
        assertFailsWith<IllegalArgumentException> {
            gateCodec.prepareSetting(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(7), fw213)
        }
        val firstValidAfterRejectedWrites = gateCodec.prepareSetting(DeviceSetting.GAIN, FieldValue.Number(2), fw213)
        assertEquals("ff0002450248aa", firstValidAfterRejectedWrites.writeFrames.single().hex())

        for ((level, tx) in listOf(
            0 to "ff0002450046aa",
            1 to "ff0002450147aa",
            2 to "ff0002450248aa",
        )) {
            val setting = createProtocolCodec(ModelProfile.I7_RS_V2)
                .prepareSetting(DeviceSetting.GAIN, FieldValue.Number(level), fw213)
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("ff0102fa4541aa", setting.readback.bytes.hex())
            assertEquals(setOf("45"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.GAIN to FieldValue.Number(level)), setting.expectedValues)
        }
        for ((level, tx) in listOf(
            0 to "ff0002460047aa",
            1 to "ff0002460148aa",
            2 to "ff0002460249aa",
            3 to "ff000246034aaa",
            4 to "ff000246044baa",
            5 to "ff000246054caa",
            6 to "ff000246064daa",
        )) {
            val setting = createProtocolCodec(ModelProfile.I7_RS_V2)
                .prepareSetting(DeviceSetting.HEARING_PROTECTION, FieldValue.Number(level), fw213)
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("ff0102fa4642aa", setting.readback.bytes.hex())
            assertEquals(setOf("46"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.HEARING_PROTECTION to FieldValue.Number(level)), setting.expectedValues)
        }

        // The firmware gate applies only to the mute value. Basic levels work with unknown firmware.
        val basicPrompt = createProtocolCodec(ModelProfile.I7_RS_V2)
            .prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(5))
        assertEquals("ff00022e0534aa", basicPrompt.writeFrames.single().hex())
        assertEquals("ff0102fa2e2aaa", basicPrompt.readback.bytes.hex())
        assertEquals(mapOf(DeviceField.PROMPT_VOLUME to FieldValue.Number(5)), basicPrompt.expectedValues)

        val promptCodec = createProtocolCodec(ModelProfile.I7_RS_V2)
        assertFailsWith<UnsupportedOperationException> {
            promptCodec.prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(0), fw213)
        }
        val muted = promptCodec.prepareSetting(
            DeviceSetting.PROMPT_VOLUME,
            FieldValue.Number(0),
            FieldValue.Firmware(listOf(3, 0, 4), "304"),
        )
        assertEquals("ff00022e002faa", muted.writeFrames.single().hex())
        assertEquals("ff0102fa2e2aaa", muted.readback.bytes.hex())
    }

    @Test
    fun invalidCustomCurvesAreRejectedBeforeAnyI7SequenceIsAllocated() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        assertFailsWith<UnsupportedOperationException> {
            createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(
                DeviceSetting.CUSTOM_EQ,
                FieldValue.IntegerList(List(10) { 0 }),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.Number(0))
        }
        val invalid = listOf(
            emptyList(),
            List(9) { 0 },
            List(11) { 0 },
            List(10) { if (it == 0) -7 else 0 },
            List(10) { if (it == 9) 7 else 0 },
        )
        for (curve in invalid) {
            assertFailsWith<IllegalArgumentException> {
                codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(curve))
            }
        }
        val valid = codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(List(10) { 0 }))
        assertEquals(
            listOf("ff00022a042faa", "ff010b3e0000000000000000000049aa"),
            valid.writeFrames.map { it.hex() },
        )
    }

    @Test
    fun i7CustomModeSlotAndCurveReadbacksKeepTheirDistinctValuesAndRawBytes() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)

        // Synthetic LTV notifications: one-byte 04 is custom; 05 + slot is custom_slot.
        val customReply = codec.feedNotification("dd00022a040daa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Text("custom"), "04"), customReply.readings[DeviceField.EQ])
        assertNull(customReply.readings[DeviceField.EQ_SLOT])
        assertEquals(setOf("2a"), customReply.responseKeys)

        val slotReply = codec.feedNotification("dd00032a050413aa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Text("custom_slot"), "0504"), slotReply.readings[DeviceField.EQ])
        assertEquals(FieldReading.Known(FieldValue.Number(4), "0504"), slotReply.readings[DeviceField.EQ_SLOT])
        assertEquals(setOf("2a"), slotReply.responseKeys)

        // Synthetic curve record reuses the independent TX example's ten symbol+absolute values.
        val curveReply = codec.feedNotification("dd000b3e86838100010203040506c5aa".bytes()).single()
        assertEquals(
            FieldReading.Known(FieldValue.IntegerList(listOf(-6, -3, -1, 0, 1, 2, 3, 4, 5, 6)), "86838100010203040506"),
            curveReply.readings[DeviceField.CUSTOM_EQ],
        )
        assertEquals(setOf("3e"), curveReply.responseKeys)
    }

    @Test
    fun i7GainProtectionAndPromptReadbacksDecodeTheirWireValuesAndRawBytes() {
        val reply = createProtocolCodec(ModelProfile.I7_RS_V2)
            .feedNotification("dd00024502024606022e00a4aa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Number(2), "02"), reply.readings[DeviceField.GAIN])
        assertEquals(FieldReading.Known(FieldValue.Number(6), "06"), reply.readings[DeviceField.HEARING_PROTECTION])
        assertEquals(FieldReading.Known(FieldValue.Number(0), "00"), reply.readings[DeviceField.PROMPT_VOLUME])
        assertEquals(setOf("45", "46", "2e"), reply.responseKeys)
    }

    @Test
    fun bassBoostUsesSlotReadbackAndLanguageUsesItsOwnRecord() {
        for (profile in listOf(ModelProfile.BUDSFEEL_MK2_RS_V2, ModelProfile.OPENFEEL_MK2_RS_V2)) {
            for ((level, frame) in listOf(
                0 to "ff00032a050031aa",
                1 to "ff00032a050132aa",
                2 to "ff00032a050233aa",
                3 to "ff00032a050334aa",
                4 to "ff00032a050435aa",
            )) {
                val codec = createProtocolCodec(profile)
                val setting = codec.prepareSetting(DeviceSetting.BASS_BOOST, FieldValue.Number(level))
                assertEquals(frame, setting.writeFrames.single().hex())
                assertEquals("ff0102fa2a26aa", setting.readback.bytes.hex())
                assertEquals(setOf(DeviceField.EQ_SLOT), setting.readback.expectedFields)
                assertEquals(mapOf(DeviceField.EQ_SLOT to FieldValue.Number(level)), setting.expectedValues)
            }
            assertFailsWith<IllegalArgumentException> {
                createProtocolCodec(profile).prepareSetting(DeviceSetting.BASS_BOOST, FieldValue.Number(5))
            }
        }
        for ((language, frame) in listOf("chinese" to "ff0002310032aa", "english" to "ff0002310133aa")) {
            val codec = createProtocolCodec(ModelProfile.JASMINE_RS_V2)
            val setting = codec.prepareSetting(DeviceSetting.PROMPT_LANGUAGE, FieldValue.Text(language))
            assertEquals(frame, setting.writeFrames.single().hex())
            assertEquals("ff0102fa312daa", setting.readback.bytes.hex())
            assertEquals(mapOf(DeviceField.PROMPT_LANGUAGE to FieldValue.Text(language)), setting.expectedValues)
        }
        // Synthetic notifications represent independent language and slot records.
        val codec = createProtocolCodec(ModelProfile.JASMINE_RS_V2)
        assertEquals(
            FieldReading.Known(FieldValue.Text("english"), "01"),
            codec.feedNotification("dd0002310111aa".bytes()).single().readings[DeviceField.PROMPT_LANGUAGE],
        )
        assertEquals(
            FieldReading.Unknown("02"),
            codec.feedNotification("dd0002310212aa".bytes()).single().readings[DeviceField.PROMPT_LANGUAGE],
        )
    }
}

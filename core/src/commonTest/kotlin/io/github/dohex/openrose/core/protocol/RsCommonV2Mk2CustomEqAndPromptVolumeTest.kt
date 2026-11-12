package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RsCommonV2Mk2CustomEqAndPromptVolumeTest {
    @Test
    fun openFeelMk2CustomEqWritesModeThenCurveAndReadsBothBack() {
        val capabilities = ModelProfile.OPENFEEL_MK2_RS_V2.capabilities()
        assertTrue(DeviceSetting.CUSTOM_EQ in capabilities.writableSettings)
        // RoseLink's OpenFeel MK2 panel has no named preset OpenRose may write; the bass-loudness-boost
        // preset is its own command and stays BASS_BOOST.
        assertEquals(emptySet<String>(), requireNotNull(capabilities.equalizer).presets)
        assertFalse(DeviceSetting.EQ in capabilities.writableSettings)
        val custom = requireNotNull(capabilities.equalizer.custom)
        // The shared EQ panel's band labels and slider bounds, not measured acoustic filter centers.
        assertEquals(listOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000), custom.bandsHz)
        assertEquals(-6, custom.minGain)
        assertEquals(6, custom.maxGain)
        // RoseLink sets no customSinceVersion for this model, so no firmware gate applies.
        for (firmware in listOf(
            FieldValue.Firmware(listOf(0, 1, 0), "010"),
            FieldValue.Firmware(listOf(3, 0, 4), "304"),
        )) {
            val gated = ModelProfile.OPENFEEL_MK2_RS_V2.capabilities(firmware)
            assertTrue(DeviceSetting.CUSTOM_EQ in gated.writableSettings, "$firmware")
            assertEquals(custom, gated.equalizer?.custom, "$firmware")
        }

        val codec = createProtocolCodec(ModelProfile.OPENFEEL_MK2_RS_V2)
        val curve = listOf(-6, -3, -1, 0, 1, 2, 3, 4, 5, 6)
        val setting = codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(curve))
        // APK setEqCustom (2A 04) then setEqCustomData (3E + ten sign-magnitude gains), worked out
        // independently for seq00/01; sign-magnitude means -1 is 0x81.
        assertEquals(listOf("ff00022a042faa", "ff010b3e86838100010203040506e8aa"), setting.writeFrames.map { it.hex() })
        // APK getEq/getCustomEq readbacks, independently worked seq02/03 literals.
        assertEquals(listOf("ff0202fa2a27aa", "ff0302fa3e3caa"), setting.readbacks.map { it.bytes.hex() })
        assertEquals(listOf(setOf(DeviceField.EQ), setOf(DeviceField.CUSTOM_EQ)), setting.readbacks.map { it.expectedFields })
        assertEquals(
            mapOf(
                DeviceField.EQ to FieldValue.Text("custom"),
                DeviceField.CUSTOM_EQ to FieldValue.IntegerList(curve),
            ),
            setting.expectedValues,
        )

        // Synthetic LTV notifications: the mode record is custom and the curve record reuses the TX bytes.
        val modeReply = codec.feedNotification("dd00022a040daa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Text("custom"), "04"), modeReply.readings[DeviceField.EQ])
        val curveReply = codec.feedNotification("dd000b3e86838100010203040506c5aa".bytes()).single()
        assertEquals(
            FieldReading.Known(FieldValue.IntegerList(curve), "86838100010203040506"),
            curveReply.readings[DeviceField.CUSTOM_EQ],
        )

        for (invalid in listOf(
            emptyList(),
            List(9) { 0 },
            List(11) { 0 },
            List(10) { if (it == 0) -7 else 0 },
            List(10) { if (it == 9) 7 else 0 },
        )) {
            assertFailsWith<IllegalArgumentException> {
                createProtocolCodec(ModelProfile.OPENFEEL_MK2_RS_V2)
                    .prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(invalid))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            createProtocolCodec(ModelProfile.OPENFEEL_MK2_RS_V2).prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.Number(0))
        }
    }

    @Test
    fun openFeelMk2BassBoostAndCustomModeKeepSeparateExpectedFields() {
        val codec = createProtocolCodec(ModelProfile.OPENFEEL_MK2_RS_V2)
        codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(List(10) { 0 }))
        val bass = codec.prepareSetting(DeviceSetting.BASS_BOOST, FieldValue.Number(1))
        // Bass boost stays on 2A 05 after the custom writes, and confirms only the boost slot.
        assertEquals("ff04032a050136aa", bass.writeFrames.single().hex())
        assertEquals("ff0502fa2a2aaa", bass.readback.bytes.hex())
        assertEquals(mapOf(DeviceField.EQ_SLOT to FieldValue.Number(1)), bass.expectedValues)
        assertEquals(setOf(DeviceField.EQ_SLOT), bass.readback.expectedFields)
    }

    @Test
    fun promptVolumeLevelsFollowEachModelsCloseOptionWithoutAFirmwareGate() {
        for ((profile, levels) in listOf(
            ModelProfile.OPENFEEL_MK2_RS_V2 to (0..5).toSet(),
            ModelProfile.BUDSFEEL_MK2_RS_V2 to (1..5).toSet(),
            ModelProfile.SWIFT2_RS_V2 to (1..5).toSet(),
            ModelProfile.JASMINE_RS_V2 to (1..5).toSet(),
        )) {
            for (firmware in listOf(
                null,
                FieldValue.Firmware(listOf(0, 1, 0), "010"),
                FieldValue.Firmware(listOf(3, 0, 4), "304"),
            )) {
                val capabilities = profile.capabilities(firmware)
                assertTrue(DeviceSetting.PROMPT_VOLUME in capabilities.writableSettings, "$profile $firmware")
                assertEquals(levels, capabilities.promptVolumeLevels, "$profile $firmware")
            }
            // 2E value for seq00; the checksum advances with the level.
            val frames = mapOf(
                0 to "ff00022e002faa",
                1 to "ff00022e0130aa",
                2 to "ff00022e0231aa",
                3 to "ff00022e0332aa",
                4 to "ff00022e0433aa",
                5 to "ff00022e0534aa",
            )
            for (level in levels) {
                val setting = createProtocolCodec(profile).prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level))
                assertEquals(frames.getValue(level), setting.writeFrames.single().hex(), "$profile $level")
                assertEquals(mapOf(DeviceField.PROMPT_VOLUME to FieldValue.Number(level)), setting.expectedValues, "$profile $level")
                assertEquals(setOf(DeviceField.PROMPT_VOLUME), setting.readback.expectedFields, "$profile $level")
            }
            if (0 !in levels) {
                assertFailsWith<UnsupportedOperationException> {
                    createProtocolCodec(profile).prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(0))
                }
            }
            for (invalid in listOf(-1, 6)) {
                assertFailsWith<IllegalArgumentException>("$profile $invalid") {
                    createProtocolCodec(profile).prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(invalid))
                }
            }
            assertFailsWith<IllegalArgumentException> {
                createProtocolCodec(profile).prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Toggle(true))
            }
        }
        // Synthetic readbacks: 2E 00 is the off option OpenFeel MK2 offers, 2E 05 the top level.
        val codec = createProtocolCodec(ModelProfile.OPENFEEL_MK2_RS_V2)
        assertEquals(
            FieldReading.Known(FieldValue.Number(0), "00"),
            codec.feedNotification("dd00022e000daa".bytes()).single().readings[DeviceField.PROMPT_VOLUME],
        )
        assertEquals(
            FieldReading.Known(FieldValue.Number(5), "05"),
            codec.feedNotification("dd00022e0512aa".bytes()).single().readings[DeviceField.PROMPT_VOLUME],
        )
    }
}

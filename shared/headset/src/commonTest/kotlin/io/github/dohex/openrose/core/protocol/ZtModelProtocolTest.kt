package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZtModelProtocolTest {
    @Test
    fun initializationUsesEachModelsDocumentedOrder() {
        val i3 = createProtocolCodec(ModelProfile.I3_ZT).initQueries(20)
        assertEquals(
            listOf(
                "08ee00000001010a0002",
                "08ee00000006020a0008",
                "08ee00000002010a0003",
                "08ee00000006030a0009",
                "08ee00000008010a0009",
                "08ee00000004010a0005",
                "08ee0000000a010a000b",
                "08ee0000000b010a000c",
            ),
            i3.map { it.bytes.hex() },
        )
        val head = createProtocolCodec(ModelProfile.HEADFEEL_ZT).initQueries(20)
        assertEquals(
            listOf(
                "0101", "0602", "0201", "0202", "0203", "0603", "0801", "0d01", "0401", "0a01",
                "0103", "0801", "0802", "0803", "0604", "0e03", "0605",
            ),
            head.map { it.key },
        )
        assertEquals("08ee00000001030a0004", head[10].bytes.hex())
        assertEquals(setOf(DeviceField.AUDIO_MODE), head[6].expectedFields)
        assertTrue(head[0].expectedFields.isEmpty())
        assertTrue(head[0].responseKeys.isEmpty())
        assertEquals(13, createProtocolCodec(ModelProfile.I5_ZT).initQueries(20).size)
    }

    @Test
    fun i3NoiseAndHeadFeelIndependentControlsUseModelSpecificWireValues() {
        val i3 = createProtocolCodec(ModelProfile.I3_ZT)
        val noise = i3.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))
        assertEquals("08ee00000006820d000100008c", noise.writeFrames.single().hex())
        val head = createProtocolCodec(ModelProfile.HEADFEEL_ZT)
        val multi = head.prepareSetting(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true))
        assertEquals("08ee00000008820b00018c", multi.writeFrames.single().hex())
        assertEquals("08ee00000008020a000a", multi.readback.bytes.hex())
        assertEquals(mapOf(DeviceField.MULTIPOINT to FieldValue.Toggle(true)), multi.expectedValues)
        assertEquals("08ee0000000e030b000315", head.prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(3)).writeFrames.single().hex())
        assertEquals("08ee00000006870b00018f", head.prepareSetting(DeviceSetting.ANC_LEVEL, FieldValue.Number(1)).writeFrames.single().hex())
        val firmware = FieldValue.Firmware(listOf(1, 3, 0), "130")
        assertEquals("08ee00000002830b000288", head.prepareSetting(DeviceSetting.GAIN, FieldValue.Number(1), firmware).writeFrames.single().hex())
    }

    @Test
    fun headFeelSyntheticRepliesKeepSingleBatteryAndIndependentMultipoint() {
        val codec = createProtocolCodec(ModelProfile.HEADFEEL_ZT)
        // 合成通知，仅按已恢复的字段偏移构造，并非实机抓包。
        val battery = codec.feedNotification("09ff00000101030a0050".bytes()).single()
        assertEquals(FieldValue.Number(80), assertIs<FieldReading.Known>(battery.readings[DeviceField.CASE_BATTERY]).value)
        assertFalse(DeviceField.LEFT_BATTERY in battery.readings)
        val audio = codec.feedNotification("09ff00000108010a0003".bytes()).single()
        assertEquals(FieldValue.Text("ldac_lhdc"), assertIs<FieldReading.Known>(audio.readings[DeviceField.AUDIO_MODE]).value)
        assertFalse(DeviceField.MULTIPOINT in audio.readings)
        val multi = codec.feedNotification("09ff00000108020a0001".bytes()).single()
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(multi.readings[DeviceField.MULTIPOINT]).value)
        val prompt = codec.feedNotification("09ff0000010e030a0003".bytes()).single()
        assertEquals(FieldValue.Number(3), assertIs<FieldReading.Known>(prompt.readings[DeviceField.PROMPT_VOLUME]).value)
        val eq = codec.feedNotification("09ff0000010202130000810200000000000000".bytes()).single()
        assertEquals(FieldValue.IntegerList(listOf(0, -1, 2, 0, 0, 0, 0, 0, 0, 0)), assertIs<FieldReading.Known>(eq.readings[DeviceField.CUSTOM_EQ]).value)
    }

    @Test
    fun headFeelCustomEqSelectsTheCurveSlotThenWritesTenSignedBytes() {
        val codec = createProtocolCodec(ModelProfile.HEADFEEL_ZT)
        val firmware = FieldValue.Firmware(listOf(1, 3, 0), "130")
        val curve = mutableListOf(3, 0, -1, 2, -6, 6, 0, -2, 4, -3)
        val setting = codec.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(curve), firmware)
        // Static evidence: setEqCustom's fixed `03` selector, then setEqCustomData's ten sign-magnitude bytes
        // (docs/protocols/models/headfeel-protocol.md). Checksums follow the documented 08EE layout; the curve
        // frame is 20 bytes, the default BLE write capacity.
        assertEquals(
            listOf("08ee00000002810b000387", "08ee0000000282140003008102860600820483a9"),
            setting.writeFrames.map { it.hex() },
        )
        assertEquals(20, setting.writeFrames.last().size)
        assertEquals("0201", setting.readback.key)
        assertEquals(listOf("0202"), setting.additionalReadbacks.map { it.key })
        assertEquals(listOf(setOf("0201"), setOf("0202")), setting.readbacks.map { it.responseKeys })
        assertEquals(listOf(setOf(DeviceField.EQ), setOf(DeviceField.CUSTOM_EQ)), setting.readbacks.map { it.expectedFields })
        curve[0] = -6
        assertEquals(
            mapOf(
                DeviceField.EQ to FieldValue.Text("custom"),
                DeviceField.CUSTOM_EQ to FieldValue.IntegerList(listOf(3, 0, -1, 2, -6, 6, 0, -2, 4, -3)),
            ),
            setting.expectedValues,
        )
        val customEqQuery = codec.initQueries(20).single { it.key == "0202" }
        assertEquals("08ee00000002020a0004", customEqQuery.bytes.hex())
        assertEquals(setOf(DeviceField.CUSTOM_EQ), customEqQuery.expectedFields)
        // 合成通知：文档给出的 0202 字段偏移，符号位加绝对值解码。
        val reply = codec.feedNotification("09ff0000010202130003008102860600820483".bytes()).single()
        assertEquals(
            FieldValue.IntegerList(listOf(3, 0, -1, 2, -6, 6, 0, -2, 4, -3)),
            assertIs<FieldReading.Known>(reply.readings[DeviceField.CUSTOM_EQ]).value,
        )
    }

    @Test
    fun headFeelCustomEqRejectsBadCurvesBeforeFraming() {
        val codec = createProtocolCodec(ModelProfile.HEADFEEL_ZT)
        val firmware = FieldValue.Firmware(listOf(1, 3, 0), "130")
        for (bad in listOf(
            FieldValue.IntegerList(emptyList()),
            FieldValue.IntegerList(List(9) { 0 }),
            FieldValue.IntegerList(List(11) { 0 }),
            FieldValue.IntegerList(List(10) { 0 }.toMutableList().also { it[4] = 7 }),
            FieldValue.IntegerList(List(10) { 0 }.toMutableList().also { it[4] = -7 }),
            FieldValue.Text("custom"),
        )) {
            assertFailsWith<IllegalArgumentException>("$bad") { codec.prepareSetting(DeviceSetting.CUSTOM_EQ, bad, firmware) }
        }
        // The firmware gate lives in the model capabilities, so old or unread firmware has no curve to write.
        val flat = FieldValue.IntegerList(List(10) { 0 })
        assertFailsWith<UnsupportedOperationException> {
            codec.prepareSetting(DeviceSetting.CUSTOM_EQ, flat, FieldValue.Firmware(listOf(1, 2, 9), "129"))
        }
        assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.CUSTOM_EQ, flat) }
    }

    @Test
    fun i3HiddenSearchReplyRemainsUnknownAndHeadFeelFirmwareGatesCustomAudio() {
        // 合成通知；隐藏搜索接收极性尚未闭合。
        val result = createProtocolCodec(ModelProfile.I3_ZT).feedNotification("09ff0000010b010a0001".bytes()).single()
        assertIs<FieldReading.Unknown>(result.readings[DeviceField.HIDE_SEARCH])
        assertFalse(result.accepted)
        val profile = ModelProfile.HEADFEEL_ZT
        val firmware129 = FieldValue.Firmware(listOf(1, 2, 9), "129")
        val firmware130 = FieldValue.Firmware(listOf(1, 3, 0), "130")
        val firmware131 = FieldValue.Firmware(listOf(1, 3, 1), "131")
        assertFalse(DeviceSetting.GAIN in profile.capabilities().writableSettings)
        assertFalse(DeviceSetting.GAIN in profile.capabilities(firmware129).writableSettings)
        assertTrue(DeviceSetting.GAIN in profile.capabilities(firmware130).writableSettings)
        assertFalse(DeviceSetting.CUSTOM_EQ in profile.capabilities().writableSettings)
        assertFalse(DeviceSetting.CUSTOM_EQ in profile.capabilities(firmware129).writableSettings)
        assertTrue(DeviceSetting.CUSTOM_EQ in profile.capabilities(firmware130).writableSettings)
        assertTrue(DeviceSetting.CUSTOM_EQ in profile.capabilities(firmware131).writableSettings)
        // The curve bands and bounds are the recovered panel's labels, not measured filter centers.
        assertNull(profile.capabilities(firmware129).equalizer?.custom)
        val custom = requireNotNull(profile.capabilities(firmware130).equalizer?.custom)
        assertEquals(listOf(20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000), custom.bandsHz)
        assertEquals(-6, custom.minGain)
        assertEquals(6, custom.maxGain)
        assertTrue(DeviceField.CUSTOM_EQ in profile.capabilities().readableFields)
        // EarFree i5 removes the field because its recovered configuration has no custom curve.
        assertFalse(DeviceField.CUSTOM_EQ in ModelProfile.I5_ZT.capabilities().readableFields)
        // Selecting the custom preset is gated by the same firmware boundary.
        val codec = createProtocolCodec(profile)
        assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("custom"), firmware129) }
        assertEquals(
            "08ee00000002810b000387",
            codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("custom"), firmware130).writeFrames.single().hex(),
        )
    }
}

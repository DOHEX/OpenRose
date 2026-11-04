package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.noiseControlCapabilities
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseProtocolTest {
    @Test
    fun i5AncWriteAndReadbackMatchIndependentApkLiterals() {
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        val setting = codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))

        // RoseLink APK fixed sender extraction; neither frame is computed by this test.
        assertEquals("08ee00000006820e00010000008d", setting.writeFrames.single().hex())
        assertEquals("08ee00000006020a0008", setting.readback.bytes.hex())
        assertEquals(setOf("0602"), setting.readback.responseKeys)
        assertEquals(mapOf(DeviceField.NOISE_MODE to FieldValue.Text("anc")), setting.expectedValues)
    }

    @Test
    fun onlyModelConfiguredNoiseModesHaveAnExecutablePlan() {
        val i5 = createProtocolCodec(ModelProfile.I5_ZT)
        // Independently extracted ZtSender fixed commands from RoseLink 3.8.0.
        for ((mode, literal) in listOf(
            "normal" to "08ee00000006820e00000100008d",
            "transparency" to "08ee00000006820e00000000018d",
            "wind" to "08ee00000006820e00000001008d",
        )) {
            assertEquals(literal, i5.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text(mode)).writeFrames.single().hex())
        }
        assertEquals(setOf("normal", "transparency", "wind", "anc"), noiseControlCapabilities(ModelProfile.I5_ZT).modes)
        assertEquals(setOf("normal", "transparency", "wind", "anc", "auto", "dynamic"), noiseControlCapabilities(ModelProfile.I7_RS_V2).modes)
    }

    @Test
    fun levelControlsHaveIndependentOptionsAndVisibleNoiseModes() {
        for (profile in ModelProfile.entries) {
            val noise = noiseControlCapabilities(profile)
            val anc = assertNotNull(noise.levelCapabilities(DeviceSetting.ANC_LEVEL), "$profile ANC levels")
            val transparency = assertNotNull(
                noise.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL),
                "$profile transparency levels",
            )

            assertEquals(setOf(1, 3, 5), anc.levels, "$profile ANC options")
            assertEquals(setOf("anc"), anc.visibleModes, "$profile ANC visibility")
            assertEquals(setOf(1, 3, 5), transparency.levels, "$profile transparency options")
            assertEquals(setOf("transparency"), transparency.visibleModes, "$profile transparency visibility")

            assertTrue(anc.isVisibleIn("anc"), "$profile ANC should be visible in ANC mode")
            assertFalse(anc.isVisibleIn("normal"), "$profile ANC should be hidden in normal mode")
            assertFalse(anc.isVisibleIn("wind"), "$profile ANC should be hidden in wind mode")
            assertFalse(transparency.isVisibleIn("wind"), "$profile transparency should be hidden in wind mode")
            assertFalse(transparency.isVisibleIn("anc"), "$profile transparency should be hidden in ANC mode")
            assertFalse(anc.isVisibleIn(null), "$profile ANC should be hidden while mode is unknown")
            assertFalse(transparency.isVisibleIn("unrecognized"), "$profile transparency should be hidden for an unknown mode")
        }

        val i7 = noiseControlCapabilities(ModelProfile.I7_RS_V2)
        assertFalse(assertNotNull(i7.levelCapabilities(DeviceSetting.ANC_LEVEL)).isVisibleIn("auto"))
        assertFalse(assertNotNull(i7.levelCapabilities(DeviceSetting.ANC_LEVEL)).isVisibleIn("dynamic"))
        assertFalse(assertNotNull(i7.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL)).isVisibleIn("auto"))
        assertFalse(assertNotNull(i7.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL)).isVisibleIn("dynamic"))
    }

    @Test
    fun levelCapabilityCanRepresentDifferentRangesAndUnsupportedControls() {
        // The recovered CERAMICS-U config has ANC low/medium/high but only low/high transparency.
        val fixture = noiseControlCapabilities(ModelProfile.I5_ZT).copy(
            ancLevel = NoiseLevelCapabilities(persistentSetOf(1, 3, 5), persistentSetOf("anc")),
            transparencyLevel = NoiseLevelCapabilities(persistentSetOf(1, 5), persistentSetOf("transparency")),
        )

        assertEquals(persistentSetOf(1, 3, 5), fixture.levelCapabilities(DeviceSetting.ANC_LEVEL)?.levels)
        assertEquals(persistentSetOf(1, 5), fixture.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL)?.levels)

        val withoutAncLevels = fixture.copy(ancLevel = null)
        assertNull(withoutAncLevels.levelCapabilities(DeviceSetting.ANC_LEVEL))
        assertNotNull(withoutAncLevels.levelCapabilities(DeviceSetting.TRANSPARENCY_LEVEL))
        assertNull(fixture.levelCapabilities(DeviceSetting.NOISE_MODE))

        val withoutAncOptions = fixture.copy(
            ancLevel = NoiseLevelCapabilities(persistentSetOf(), persistentSetOf("anc")),
        )
        assertFalse(assertNotNull(withoutAncOptions.levelCapabilities(DeviceSetting.ANC_LEVEL)).isVisibleIn("anc"))
    }

    @Test
    fun i7ModesMatchIndependentSenderLiteralsAndReadActualMode() {
        for ((mode, literal) in listOf(
            "anc" to "ff000209010baa", "normal" to "ff000209020caa",
            "transparency" to "ff000209030daa", "wind" to "ff000209040eaa",
            "auto" to "ff000209050faa", "dynamic" to "ff0002090711aa",
        )) {
            val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
            val setting = codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text(mode))
            assertEquals(literal, setting.writeFrames.single().hex(), mode)
            // A client single-field FA query, independently worked seq01 example.
            assertEquals("ff0102fa0905aa", setting.readback.bytes.hex(), mode)
            assertEquals(setOf("09"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.NOISE_MODE to FieldValue.Text(mode)), setting.expectedValues)
        }
    }

    @Test
    fun ancAndTransparencyUseTheirOwnModelLevelMapping() {
        // Dynamic worked examples from independent rose_reverse tests and sender parameter evidence.
        val vectors = listOf(
            Triple(ModelProfile.I5_ZT, DeviceSetting.ANC_LEVEL, listOf("08ee00000006870b00008e", "08ee00000006870b00018f", "08ee00000006870b000290")),
            Triple(ModelProfile.I5_ZT, DeviceSetting.TRANSPARENCY_LEVEL, listOf("08ee00000006840b00008b", "08ee00000006840b00018c", "08ee00000006840b00028d")),
            Triple(ModelProfile.I7_RS_V2, DeviceSetting.ANC_LEVEL, listOf("ff00022c012eaa", "ff00022c0330aa", "ff00022c0532aa")),
            Triple(ModelProfile.I7_RS_V2, DeviceSetting.TRANSPARENCY_LEVEL, listOf("ff00022d012faa", "ff00022d0331aa", "ff00022d0533aa")),
        )
        for ((profile, kind, literals) in vectors) {
            assertEquals(persistentSetOf(1, 3, 5), noiseControlCapabilities(profile).levelCapabilities(kind)?.levels)
            for ((index, level) in listOf(1, 3, 5).withIndex()) {
                val setting = createProtocolCodec(profile).prepareSetting(kind, FieldValue.Number(level))
                assertEquals(literals[index], setting.writeFrames.single().hex(), "$profile $kind $level")
                val field = if (kind == DeviceSetting.ANC_LEVEL) DeviceField.ANC_LEVEL else DeviceField.TRANSPARENCY_LEVEL
                assertEquals(setOf(field), setting.readback.expectedFields)
                assertEquals(mapOf(field to FieldValue.Number(level)), setting.expectedValues)
            }
        }
    }

    @Test
    fun cycleFlagsUseModelWireOrderAndKeepAnIndependentExpectedSnapshot() {
        val flags = linkedMapOf<String, Boolean?>("normal" to true, "transparency" to false, "wind" to false, "anc" to true)
        val input = FieldValue.NoiseCycle(flags)
        val i5 = createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(DeviceSetting.NOISE_CYCLE, input)
        val i7 = createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.NOISE_CYCLE, input)
        // i5 independent dynamic example; i7 sender order ANC/wind/normal/transparency.
        assertEquals("08ee00000006850e000100000191", i5.writeFrames.single().hex())
        assertEquals("ff000536010001003caa", i7.writeFrames.single().hex())
        assertEquals("08ee00000006050a000b", i5.readback.bytes.hex())
        assertEquals("ff0102fa3632aa", i7.readback.bytes.hex())
        flags["normal"] = false
        assertEquals(true, (i5.expectedValues.getValue(DeviceField.NOISE_CYCLE) as FieldValue.NoiseCycle).flags["normal"])
        assertEquals(true, (i7.expectedValues.getValue(DeviceField.NOISE_CYCLE) as FieldValue.NoiseCycle).flags["normal"])
        for (profile in ModelProfile.entries) {
            assertEquals(0, noiseControlCapabilities(profile).minimumCycleSelections)
            val none = FieldValue.NoiseCycle(noiseControlCapabilities(profile).cycleModes.associateWith { false })
            assertEquals(none, createProtocolCodec(profile).prepareSetting(DeviceSetting.NOISE_CYCLE, none).expectedValues[DeviceField.NOISE_CYCLE])
        }
    }

    @Test
    fun invalidValuesAndUnsupportedModesNeverAllocateAnI7Sequence() {
        for (profile in ModelProfile.entries) {
            val codec = createProtocolCodec(profile)
            for ((kind, value) in listOf(
                DeviceSetting.NOISE_MODE to FieldValue.Toggle(true),
                DeviceSetting.ANC_LEVEL to FieldValue.Number(2),
                DeviceSetting.TRANSPARENCY_LEVEL to FieldValue.Number(0),
                DeviceSetting.ANC_LEVEL to FieldValue.Text("3"),
                DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(mapOf("anc" to true)),
                DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(mapOf("anc" to null, "normal" to false, "wind" to false, "transparency" to false)),
                DeviceSetting.NOISE_CYCLE to FieldValue.NoiseCycle(mapOf("anc" to true, "normal" to false, "wind" to false, "transparency" to false, "auto" to true)),
            )) {
                assertFailsWith<IllegalArgumentException>("$profile $kind $value") { codec.prepareSetting(kind, value) }
            }
            assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("deep")) }
            assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("custom")) }
            if (profile == ModelProfile.I5_ZT) {
                assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("auto")) }
                assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("dynamic")) }
            } else {
                assertEquals("ff000209010baa", codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")).writeFrames.single().hex())
            }
        }
    }

    @Test
    fun i7InitializationWritesAndReadbacksShareSequenceUntilReset() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        codec.initQueries(20) // Three FA info groups, then a curve query: sequences 00..03.
        val dynamic = codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("dynamic"))
        assertEquals("ff0402090715aa", dynamic.writeFrames.single().hex())
        assertEquals("ff0502fa0909aa", dynamic.readback.bytes.hex())
        codec.reset()
        repeat(127) { codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")) }
        val last = codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))
        assertEquals("fffe02090109aa", last.writeFrames.single().hex())
        assertEquals("ffff02fa0903aa", last.readback.bytes.hex())
        assertEquals("ff000209010baa", codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")).writeFrames.single().hex())
        codec.reset()
        assertEquals("ff000209010baa", codec.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc")).writeFrames.single().hex())
    }

    @Test
    fun readCommandsSupplyStateAndWriteOpcodeEchoDoesNotConfirmI5() {
        val i5 = createProtocolCodec(ModelProfile.I5_ZT)
        // Synthetic RX buffers at independently recovered offsets, not device captures.
        val echo = i5.feedNotification("0000000000068200000100000020".bytes()).single()
        assertFalse(echo.accepted)
        assertFalse(DeviceField.NOISE_MODE in echo.readings)
        val i5State = i5.feedNotification("0000000000060200000000010020".bytes()).single()
        assertEquals(setOf("0602"), i5State.responseKeys)
        assertEquals(FieldValue.Text("wind"), assertIs<FieldReading.Known>(i5State.readings[DeviceField.NOISE_MODE]).value)
        // i7 has no captures; known field09=04 is the actual reported wind mode.
        val i7State = createProtocolCodec(ModelProfile.I7_RS_V2).feedNotification("dd00020904ecaa".bytes()).single()
        assertEquals(FieldValue.Text("wind"), assertIs<FieldReading.Known>(i7State.readings[DeviceField.NOISE_MODE]).value)
    }
}

package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class F8CodecTest {
    @Test
    fun initializationUsesDocumentedQueriesAndCapacity() {
        val codec = F8Codec(ModelProfile.AGATE_F8)
        assertFailsWith<IllegalArgumentException> { codec.initQueries(5) }
        assertEquals(
            listOf("002701000101", "00270100010c", "002701000104", "002701000108", "00270100010b", "002701000102", "022701000105", "002701000109", "002701000113", "002701000111", "002701000116"),
            codec.initQueries(6).map { it.bytes.hex() },
        )
        assertEquals("00270100010a", F8Codec(ModelProfile.EXPLORER_F8).initQueries(6).last().bytes.hex())
    }

    @Test
    fun settingsUseIndependentDocumentedFramesAndReadbacks() {
        val x = F8Codec(ModelProfile.CERAMICS_X_F8)
        val anc = x.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("anc"))
        assertContentEquals("002c01000101".bytes(), anc.writeFrames.single())
        assertContentEquals("00270100010c".bytes(), anc.readback.bytes)
        val explorer = F8Codec(ModelProfile.EXPLORER_F8)
        assertContentEquals("002c01000100".bytes(), explorer.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("normal")).writeFrames.single())
        assertContentEquals("00200100020002".bytes(), x.prepareSetting(DeviceSetting.EQ, FieldValue.Text("rock")).writeFrames.single())
        assertContentEquals("0022010003080109".bytes(), x.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("right", "long", "noise_cycle")).writeFrames.single())
        assertFailsWith<UnsupportedOperationException> { F8Codec(ModelProfile.AGATE_X_F8).prepareSetting(DeviceSetting.GAME_MODE, FieldValue.Toggle(true)) }
    }

    @Test
    fun syntheticRepliesAreBufferedAndDecodedWithoutChecksum() {
        // Synthetic RX, using the documented header, tag and byte offsets; not a hardware capture.
        val frame = "0027010005010050da32".bytes()
        val codec = F8Codec(ModelProfile.CERAMICS_X_F8)
        assertTrue(codec.feedNotification(frame.copyOfRange(0, 4)).isEmpty())
        val reply = codec.feedNotification(frame.copyOfRange(4, frame.size)).single()
        assertEquals(FieldValue.Number(80), assertIs<FieldReading.Known>(reply.readings[DeviceField.LEFT_BATTERY]).value)
        assertEquals(FieldValue.Number(90), assertIs<FieldReading.Known>(reply.readings[DeviceField.RIGHT_BATTERY]).value)
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(reply.readings[DeviceField.RIGHT_CHARGING]).value)
        assertTrue(reply.accepted)
        codec.feedNotification(frame.copyOfRange(0, 4))
        codec.reset()
        assertTrue(codec.feedNotification(frame.copyOfRange(4, frame.size)).isEmpty())
    }

    @Test
    fun syntheticUnknownValuesDoNotConfirmSettings() {
        // Synthetic RX: 0x28 shares the same dispatch as 0x27.
        val codec = F8Codec(ModelProfile.CERAMICS_F8)
        val ambiguous = codec.feedNotification("0028010003040002".bytes()).single()
        assertIs<FieldReading.Unknown>(ambiguous.readings[DeviceField.EQ])
        assertFalse(ambiguous.accepted)
        val invalid = F8Codec(ModelProfile.EXPLORER_F8).feedNotification("00270100030c00ff".bytes()).single()
        assertFalse(invalid.accepted)
    }

    @Test
    fun audioAndMultipointReadbackConfirmsBothCoupledFields() {
        val codec = createProtocolCodec(ModelProfile.CERAMICS_X_F8)
        val ldac = codec.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("ldac"))
        assertEquals("002b01000101", ldac.writeFrames.single().hex())
        assertEquals("00270100010b", ldac.readback.bytes.hex())
        // Synthetic notifications: LDAC disables multipoint, while wire 00 enables it with AAC/SBC.
        val audio = codec.feedNotification("00270100030b0001".bytes()).single().readings
        val expectedAudio = mapOf(
            DeviceField.AUDIO_MODE to FieldValue.Text("ldac"),
            DeviceField.MULTIPOINT to FieldValue.Toggle(false),
        )
        assertEquals(expectedAudio, audio.mapValues { assertIs<FieldReading.Known>(it.value).value })
        assertEquals(expectedAudio, ldac.expectedValues)
        val multi = codec.prepareSetting(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true))
        assertEquals("002b01000100", multi.writeFrames.single().hex())
        val both = codec.feedNotification("00270100030b0000".bytes()).single().readings
        assertEquals(
            mapOf(
                DeviceField.AUDIO_MODE to FieldValue.Text("aac_sbc"),
                DeviceField.MULTIPOINT to FieldValue.Toggle(true),
            ),
            both.mapValues { assertIs<FieldReading.Known>(it.value).value },
        )
        val disabled = codec.prepareSetting(DeviceSetting.MULTIPOINT, FieldValue.Toggle(false))
        assertEquals("002b01000102", disabled.writeFrames.single().hex())
        assertEquals(FieldValue.Text("aac_sbc"), disabled.expectedValues[DeviceField.AUDIO_MODE])
        assertEquals(FieldValue.Toggle(false), disabled.expectedValues[DeviceField.MULTIPOINT])
    }

    @Test
    fun explorerLanguageAndDetectionUseIndependentQueriesAndRejectUnknownReadings() {
        val codec = createProtocolCodec(ModelProfile.EXPLORER_F8)
        val language = codec.prepareSetting(DeviceSetting.PROMPT_LANGUAGE, FieldValue.Text("english"))
        assertEquals("002901000101", language.writeFrames.single().hex())
        assertEquals("00270100010a", language.readback.bytes.hex())
        // Synthetic language and detection records, not device captures.
        val reply = codec.feedNotification("00270100030a0001".bytes()).single()
        assertEquals(FieldValue.Text("english"), assertIs<FieldReading.Known>(reply.readings[DeviceField.PROMPT_LANGUAGE]).value)
        val detection = codec.prepareSetting(DeviceSetting.EAR_DETECTION, FieldValue.Toggle(true))
        assertEquals("002601000101", detection.writeFrames.single().hex())
        val enabled = codec.feedNotification("0027010003090001".bytes()).single()
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(enabled.readings[DeviceField.EAR_DETECTION]).value)
        val invalid = codec.feedNotification("00270100030900ff".bytes()).single()
        assertIs<FieldReading.Unknown>(invalid.readings[DeviceField.EAR_DETECTION])
    }

    @Test
    fun explorerPromptVolumeUsesDingLevelAndLeavesOffClosed() {
        assertEquals((1..5).toSet(), ModelProfile.EXPLORER_F8.capabilities().promptVolumeLevels)
        val codec = F8Codec(ModelProfile.EXPLORER_F8)
        for (level in 1..5) {
            val setting = codec.prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level))
            assertEquals("00320100010$level", setting.writeFrames.single().hex())
            assertEquals("002701000113", setting.readback.bytes.hex())
            assertEquals(mapOf(DeviceField.PROMPT_VOLUME to FieldValue.Number(level)), setting.expectedValues)
        }
        // Synthetic ding-level reply, not a device capture.
        val reply = codec.feedNotification("0027010003130003".bytes()).single()
        assertEquals(FieldValue.Number(3), assertIs<FieldReading.Known>(reply.readings[DeviceField.PROMPT_VOLUME]).value)
        for (level in listOf(0, 6)) {
            assertFailsWith<UnsupportedOperationException>("level $level") {
                codec.prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level))
            }
        }
        assertFailsWith<IllegalArgumentException> { codec.prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(10)) }
    }

    @Test
    fun explorerNoiseLevelsInvertOnWriteAndDecodeOnFreshReadback() {
        val codec = F8Codec(ModelProfile.EXPLORER_F8)
        for ((level, wire) in listOf(1 to 2, 3 to 1, 5 to 0)) {
            val anc = codec.prepareSetting(DeviceSetting.ANC_LEVEL, FieldValue.Number(level))
            assertEquals("00300100010$wire", anc.writeFrames.single().hex())
            assertEquals("002701000111", anc.readback.bytes.hex())
            assertEquals(mapOf(DeviceField.ANC_LEVEL to FieldValue.Number(level)), anc.expectedValues)
            val transparency = codec.prepareSetting(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(level))
            assertEquals("00310100010$wire", transparency.writeFrames.single().hex())
            assertEquals("002701000112", transparency.readback.bytes.hex())
            assertEquals(mapOf(DeviceField.TRANSPARENCY_LEVEL to FieldValue.Number(level)), transparency.expectedValues)
        }
        // Synthetic 11/12 replies, not device captures: wire 0/1/2 are the UI levels 5/3/1.
        for ((raw, level) in listOf(0 to 5, 1 to 3, 2 to 1)) {
            val wire = raw.toString(16).padStart(2, '0')
            val anc = codec.feedNotification("00270100031100$wire".bytes()).single()
            assertEquals(FieldValue.Number(level), assertIs<FieldReading.Known>(anc.readings[DeviceField.ANC_LEVEL]).value)
            assertTrue(anc.accepted)
            val transparency = codec.feedNotification("00270100031200$wire".bytes()).single()
            assertEquals(
                FieldValue.Number(level),
                assertIs<FieldReading.Known>(transparency.readings[DeviceField.TRANSPARENCY_LEVEL]).value,
            )
            assertTrue(transparency.accepted)
        }
        assertFailsWith<IllegalArgumentException> { codec.prepareSetting(DeviceSetting.ANC_LEVEL, FieldValue.Number(2)) }
        assertFailsWith<IllegalArgumentException> {
            codec.prepareSetting(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(0))
        }
    }

    @Test
    fun ceramicsXModelsKeepNoiseLevelsAndPromptVolumeClosed() {
        for (profile in listOf(ModelProfile.CERAMICS_X_F8, ModelProfile.CERAMICS_X_7034_F8)) {
            val codec = F8Codec(profile)
            for (setting in listOf(DeviceSetting.ANC_LEVEL, DeviceSetting.TRANSPARENCY_LEVEL, DeviceSetting.PROMPT_VOLUME)) {
                assertFailsWith<UnsupportedOperationException>("$profile $setting") {
                    codec.prepareSetting(setting, FieldValue.Number(3))
                }
            }
            // The shared CERAMICS-X receive handler leaves both level tags Unknown for these models.
            for ((tag, field) in listOf("11" to DeviceField.ANC_LEVEL, "12" to DeviceField.TRANSPARENCY_LEVEL)) {
                val reply = codec.feedNotification("0027010003${tag}0001".bytes()).single()
                assertIs<FieldReading.Unknown>(reply.readings[field])
                assertFalse(reply.accepted)
            }
        }
    }
}

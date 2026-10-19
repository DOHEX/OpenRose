package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.audioControlCapabilities
import io.github.dohex.openrose.core.promptVolumeLevels
import io.github.dohex.openrose.core.touchControlCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse

class DeviceControlProtocolTest {
    @Test
    fun capabilitiesKeepModelTouchActionsAudioCouplingAndPromptLevelsSeparate() {
        // RoseLink model lists: pp+b6c0/i5 eight actions, pp+b228/i7 nine; both typeFull.
        val slots = setOf(
            TouchSlot("left", "single"), TouchSlot("left", "double"), TouchSlot("left", "triple"), TouchSlot("left", "long"),
            TouchSlot("right", "single"), TouchSlot("right", "double"), TouchSlot("right", "triple"), TouchSlot("right", "long"),
        )
        val i5Actions = setOf("volume_up", "volume_down", "previous_track", "next_track", "voice_assistant", "play_pause", "none", "noise_cycle")
        assertEquals(slots, touchControlCapabilities(ModelProfile.I5_ZT).slots)
        assertEquals(slots, touchControlCapabilities(ModelProfile.I7_RS_V2).slots)
        assertEquals(i5Actions, touchControlCapabilities(ModelProfile.I5_ZT).actions)
        assertEquals(i5Actions + "game_mode", touchControlCapabilities(ModelProfile.I7_RS_V2).actions)
        assertEquals(AudioControlCapabilities(setOf("aac_sbc", "ldac"), true), audioControlCapabilities(ModelProfile.I5_ZT))
        assertEquals(AudioControlCapabilities(setOf("aac_sbc", "ldac", "lhdc", "lc3"), false), audioControlCapabilities(ModelProfile.I7_RS_V2))
        assertEquals(setOf(1, 2, 3, 4, 5), promptVolumeLevels(ModelProfile.I5_ZT))
        assertEquals(emptySet(), promptVolumeLevels(ModelProfile.I7_RS_V2))
    }

    @Test
    fun i5AudioAndMultipointUseTheSameModelCommandAndConfirmBothFields() {
        // Independent parent ZtSender.setConnectMulOn and shared setAudioLdac literals.
        // The shared 0882 variant is not the i5 configuration's command.
        for ((kind, value, multipoint) in listOf(
            Triple(DeviceSetting.AUDIO_MODE, FieldValue.Text("aac_sbc"), true),
            Triple(DeviceSetting.AUDIO_MODE, FieldValue.Text("ldac"), false),
            Triple(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true), true),
            Triple(DeviceSetting.MULTIPOINT, FieldValue.Toggle(false), false),
        )) {
            val setting = createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(kind, value)
            assertEquals(if (multipoint) "08ee00000008810b00008a" else "08ee00000008810b00018b", setting.writeFrames.single().hex())
            assertEquals("08ee00000008010a0009", setting.readback.bytes.hex())
            assertEquals(setOf("0801"), setting.readback.responseKeys)
            assertEquals(setOf(DeviceField.AUDIO_MODE, DeviceField.MULTIPOINT), setting.readback.expectedFields)
            assertEquals(mapOf(
                DeviceField.AUDIO_MODE to FieldValue.Text(if (multipoint) "aac_sbc" else "ldac"),
                DeviceField.MULTIPOINT to FieldValue.Toggle(multipoint),
            ), setting.expectedValues)
        }
    }

    @Test
    fun i7AudioAndMultipointWriteAndConfirmTheirIndependentFields() {
        // Independent RsCommonV2Sender fixed literals, all seq00.
        for ((mode, tx) in listOf(
            "aac_sbc" to "ff00022b002caa", "ldac" to "ff00022b012daa",
            "lhdc" to "ff00022b022eaa", "lc3" to "ff00022b032faa",
        )) {
            val setting = createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text(mode))
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("ff0102fa2b27aa", setting.readback.bytes.hex())
            assertEquals(setOf("2b"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.AUDIO_MODE to FieldValue.Text(mode)), setting.expectedValues)
        }
        for ((enabled, tx) in listOf(true to "ff0002320134aa", false to "ff0002320033aa")) {
            val setting = createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.MULTIPOINT, FieldValue.Toggle(enabled))
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("ff0102fa322eaa", setting.readback.bytes.hex())
            assertEquals(setOf("32"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.MULTIPOINT to FieldValue.Toggle(enabled)), setting.expectedValues)
        }
    }

    @Test
    fun touchAndDetectionUseZeroForEnabledOnBothModels() {
        val vectors = listOf(
            listOf(ModelProfile.I5_ZT, DeviceSetting.TOUCH_ENABLED, true, "08ee0000000a810b00008c", "08ee0000000a010a000b"),
            listOf(ModelProfile.I5_ZT, DeviceSetting.TOUCH_ENABLED, false, "08ee0000000a810b00018d", "08ee0000000a010a000b"),
            listOf(ModelProfile.I5_ZT, DeviceSetting.EAR_DETECTION, true, "08ee0000000c810b00008e", "08ee0000000c010a000d"),
            listOf(ModelProfile.I5_ZT, DeviceSetting.EAR_DETECTION, false, "08ee0000000c810b00018f", "08ee0000000c010a000d"),
            listOf(ModelProfile.I7_RS_V2, DeviceSetting.TOUCH_ENABLED, true, "ff0002070008aa", "ff0102fa0703aa"),
            listOf(ModelProfile.I7_RS_V2, DeviceSetting.TOUCH_ENABLED, false, "ff0002070109aa", "ff0102fa0703aa"),
            listOf(ModelProfile.I7_RS_V2, DeviceSetting.EAR_DETECTION, true, "ff0002080009aa", "ff0102fa0804aa"),
            listOf(ModelProfile.I7_RS_V2, DeviceSetting.EAR_DETECTION, false, "ff000208010aaa", "ff0102fa0804aa"),
        )
        for (vector in vectors) {
            val profile = vector[0] as ModelProfile
            val kind = vector[1] as DeviceSetting
            val enabled = vector[2] as Boolean
            val setting = createProtocolCodec(profile).prepareSetting(kind, FieldValue.Toggle(enabled))
            assertEquals(vector[3], setting.writeFrames.single().hex())
            assertEquals(vector[4], setting.readback.bytes.hex())
            val field = if (kind == DeviceSetting.TOUCH_ENABLED) DeviceField.TOUCH_ENABLED else DeviceField.EAR_DETECTION
            assertEquals(mapOf(field to FieldValue.Toggle(enabled)), setting.expectedValues)
        }
    }

    @Test
    fun i5SingleSlotBindingUsesItsModelActionAndProjectsOnlyTheRequestedSlot() {
        val request = FieldValue.TouchAssignment("left", "double", "play_pause")
        val setting = createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(DeviceSetting.TOUCH_BINDING, request)
        // Independent dynamic worked example in rose_reverse/tests/test_i5_protocol.py.
        assertEquals("08ee00000004810d000001058e", setting.writeFrames.single().hex())
        assertEquals("08ee00000004010a0005", setting.readback.bytes.hex())
        assertEquals(setOf("0401"), setting.readback.responseKeys)
        assertEquals(mapOf(DeviceField.TOUCH_KEYS to request), setting.expectedValues)
    }

    @Test
    fun i7SingleSlotBindingUsesItsOwnSlotIdsAndActionCodes() {
        val request = FieldValue.TouchAssignment("right", "long", "noise_cycle")
        val setting = createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.TOUCH_BINDING, request)
        // Independent dynamic worked example in rose_reverse/tests/test_i7_protocol.py.
        assertEquals("ff00030114081faa", setting.writeFrames.single().hex())
        assertEquals("ff0102fa01fdaa", setting.readback.bytes.hex())
        assertEquals(setOf("01"), setting.readback.responseKeys)
        assertEquals(mapOf(DeviceField.TOUCH_KEYS to request), setting.expectedValues)
    }

    @Test
    fun i5PromptLevelsOneThroughFiveUseZeroThroughFourAndTheModelQuery() {
        // Independently recovered Zt229Sender parameter rule and reference high-level vector.
        for ((level, tx) in listOf(
            1 to "08ee0000000e830b000092", 2 to "08ee0000000e830b000193",
            3 to "08ee0000000e830b000294", 4 to "08ee0000000e830b000395", 5 to "08ee0000000e830b000496",
        )) {
            val setting = createProtocolCodec(ModelProfile.I5_ZT).prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level))
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals("08ee0000000e030a0011", setting.readback.bytes.hex())
            assertEquals(setOf("0e03"), setting.readback.responseKeys)
            assertEquals(mapOf(DeviceField.PROMPT_VOLUME to FieldValue.Number(level)), setting.expectedValues)
        }
        // i7 prompt volume is intentionally outside this stage's implemented setting surface.
        assertFailsWith<UnsupportedOperationException> {
            createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(1))
        }
    }

    @Test
    fun everyModelSlotUsesItsOwnAddressAndAllowsTheConfiguredActions() {
        // Independently worked complete frames from recovered sender slot parameters.
        val slots = listOf(
            TouchSlot("left", "single"), TouchSlot("left", "double"), TouchSlot("left", "triple"), TouchSlot("left", "long"),
            TouchSlot("right", "single"), TouchSlot("right", "double"), TouchSlot("right", "triple"), TouchSlot("right", "long"),
        )
        val i5Frames = listOf(
            "08ee00000004810d000000058d", "08ee00000004810d000001058e",
            "08ee00000004810d000002058f", "08ee00000004810d0000030590",
            "08ee00000004810d000100058e", "08ee00000004810d000101058f",
            "08ee00000004810d0001020590", "08ee00000004810d0001030591",
        )
        val i7Frames = listOf(
            "ff000301010105aa", "ff000301020106aa", "ff000301030107aa", "ff000301040108aa",
            "ff000301110115aa", "ff000301120116aa", "ff000301130117aa", "ff000301140118aa",
        )
        for (profile in ModelProfile.entries) {
            for ((index, slot) in slots.withIndex()) {
                val request = FieldValue.TouchAssignment(slot.side, slot.gesture, "play_pause")
                val setting = createProtocolCodec(profile).prepareSetting(DeviceSetting.TOUCH_BINDING, request)
                assertEquals((if (profile == ModelProfile.I5_ZT) i5Frames else i7Frames)[index], setting.writeFrames.single().hex())
                for (action in touchControlCapabilities(profile).actions) {
                    val assignment = FieldValue.TouchAssignment(slot.side, slot.gesture, action)
                    assertEquals(mapOf(DeviceField.TOUCH_KEYS to assignment),
                        createProtocolCodec(profile).prepareSetting(DeviceSetting.TOUCH_BINDING, assignment).expectedValues)
                }
            }
        }
    }

    @Test
    fun actionNumbersAreSpecificToEachModel() {
        // Complete left/single setting frames, independently worked from APK action objects.
        val i5 = listOf(
            "volume_up" to "08ee00000004810d0000000088", "volume_down" to "08ee00000004810d0000000189",
            "previous_track" to "08ee00000004810d000000028a", "next_track" to "08ee00000004810d000000038b",
            "voice_assistant" to "08ee00000004810d000000048c", "play_pause" to "08ee00000004810d000000058d",
            "none" to "08ee00000004810d000000068e", "noise_cycle" to "08ee00000004810d000000078f",
        )
        val i7 = listOf(
            "none" to "ff000301010004aa", "play_pause" to "ff000301010105aa",
            "previous_track" to "ff000301010206aa", "next_track" to "ff000301010307aa",
            "volume_up" to "ff000301010408aa", "volume_down" to "ff000301010509aa",
            "game_mode" to "ff00030101060aaa", "voice_assistant" to "ff00030101070baa", "noise_cycle" to "ff00030101080caa",
        )
        for ((profile, cases) in listOf(ModelProfile.I5_ZT to i5, ModelProfile.I7_RS_V2 to i7)) {
            for ((action, tx) in cases) {
                val request = FieldValue.TouchAssignment("left", "single", action)
                assertEquals(tx, createProtocolCodec(profile).prepareSetting(DeviceSetting.TOUCH_BINDING, request).writeFrames.single().hex())
            }
        }
    }

    @Test
    fun invalidRequestsAreRejectedBeforeI7SequenceAllocation() {
        for (profile in ModelProfile.entries) {
            val codec = createProtocolCodec(profile)
            for ((kind, value) in listOf(
                DeviceSetting.AUDIO_MODE to FieldValue.Number(0),
                DeviceSetting.MULTIPOINT to FieldValue.Text("true"),
                DeviceSetting.TOUCH_ENABLED to FieldValue.Number(0),
                DeviceSetting.EAR_DETECTION to FieldValue.Text("on"),
                DeviceSetting.TOUCH_BINDING to FieldValue.TouchKeys(emptyList()),
                DeviceSetting.TOUCH_BINDING to FieldValue.TouchAssignment("both", "single", "none"),
                DeviceSetting.TOUCH_BINDING to FieldValue.TouchAssignment("left", "hold", "none"),
            )) {
                assertFailsWith<IllegalArgumentException>("$profile $kind $value") { codec.prepareSetting(kind, value) }
            }
            assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("opus")) }
            assertFailsWith<UnsupportedOperationException> {
                codec.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "head_tracking"))
            }
            if (profile == ModelProfile.I5_ZT) {
                for (mode in listOf("lhdc", "lc3")) {
                    assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text(mode)) }
                }
                assertFailsWith<UnsupportedOperationException> {
                    codec.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("right", "long", "game_mode"))
                }
                for (value in listOf(FieldValue.Number(0), FieldValue.Number(6), FieldValue.Number(-1), FieldValue.Toggle(false))) {
                    assertFailsWith<IllegalArgumentException> { codec.prepareSetting(DeviceSetting.PROMPT_VOLUME, value) }
                }
            } else {
                for (level in listOf(0, 1, 5)) {
                    assertFailsWith<UnsupportedOperationException> { codec.prepareSetting(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(level)) }
                }
                assertEquals("ff0002320134aa", codec.prepareSetting(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true)).writeFrames.single().hex())
            }
        }
    }

    @Test
    fun initializationTouchSwitchesAndAudioKeepOneI7Sequence() {
        val codec = createProtocolCodec(ModelProfile.I7_RS_V2)
        codec.initQueries(20) // Four complete query frames consume00..03.
        val touch = codec.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", "none"))
        assertEquals("ff040301010008aa", touch.writeFrames.single().hex())
        assertEquals("ff0502fa0101aa", touch.readback.bytes.hex())
        val detection = codec.prepareSetting(DeviceSetting.EAR_DETECTION, FieldValue.Toggle(true))
        assertEquals("ff060208000faa", detection.writeFrames.single().hex())
        assertEquals("ff0702fa080aaa", detection.readback.bytes.hex())
        val audio = codec.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("lc3"))
        assertEquals("ff08022b0337aa", audio.writeFrames.single().hex())
        assertEquals("ff0902fa2b2faa", audio.readback.bytes.hex())
    }

    @Test
    fun syntheticTouchReadbacksKeepOtherUnknownSlotsAndRejectStructurallyMissingSlots() {
        // Synthetic offset buffer with only left/double known; physical i5 eight-slot structure remains.
        val i5 = createProtocolCodec(ModelProfile.I5_ZT)
        val reply = i5.feedNotification("0000000000040100000f050f0f0f0f0f0f".bytes()).single()
        val bindings = assertIs<FieldValue.TouchKeys>(assertIs<FieldReading.Known>(reply.readings[DeviceField.TOUCH_KEYS]).value).bindings
        assertEquals("play_pause", bindings.single { it.side == "left" && it.gesture == "double" }.action)
        assertEquals(7, bindings.count { it.action == null })
        assertFalse(i5.feedNotification("0000000000040100000f050f0f0f0f0f".bytes()).single().accepted)

        // Synthetic LTV whole-prefix checksum, only right/long known; all eight IDs are present.
        val i7 = createProtocolCodec(ModelProfile.I7_RS_V2)
        val i7Reply = i7.feedNotification("dd00110101ff02ff03ff04ff11ff12ff13ff140844aa".bytes()).single()
        val i7Bindings = assertIs<FieldValue.TouchKeys>(assertIs<FieldReading.Known>(i7Reply.readings[DeviceField.TOUCH_KEYS]).value).bindings
        assertEquals("noise_cycle", i7Bindings.single { it.side == "right" && it.gesture == "long" }.action)
        assertEquals(7, i7Bindings.count { it.action == null })
        assertFalse(i7.feedNotification("dd0003010105e7aa".bytes()).single().accepted)
    }

    @Test
    fun syntheticSwitchPromptAndAudioReadbacksPreserveUnknownValuesAndModelRules() {
        val i5 = createProtocolCodec(ModelProfile.I5_ZT)
        // Synthetic i5 byte9 high-nibble sentinels; these are not complete device capture claims.
        for ((rx, field, expected) in listOf(
            Triple("00000000000a010000a0", DeviceField.TOUCH_ENABLED, FieldValue.Toggle(true)),
            Triple("00000000000c010000a1", DeviceField.EAR_DETECTION, FieldValue.Toggle(false)),
            Triple("00000000000e030000a0", DeviceField.PROMPT_VOLUME, FieldValue.Number(1)),
            Triple("00000000000e030000a4", DeviceField.PROMPT_VOLUME, FieldValue.Number(5)),
        )) {
            assertEquals(expected, assertIs<FieldReading.Known>(i5.feedNotification(rx.bytes()).single().readings[field]).value)
        }
        for ((rx, mode, multipoint) in listOf(
            Triple("000000000008010000a0", "aac_sbc", true), Triple("000000000008010000a1", "ldac", false),
        )) {
            val fields = i5.feedNotification(rx.bytes()).single().readings
            assertEquals(FieldValue.Text(mode), assertIs<FieldReading.Known>(fields[DeviceField.AUDIO_MODE]).value)
            assertEquals(FieldValue.Toggle(multipoint), assertIs<FieldReading.Known>(fields[DeviceField.MULTIPOINT]).value)
        }
        val unknownAudio = i5.feedNotification("000000000008010000a2".bytes()).single().readings
        assertIs<FieldReading.Unknown>(unknownAudio[DeviceField.AUDIO_MODE])
        assertIs<FieldReading.Unknown>(unknownAudio[DeviceField.MULTIPOINT])
        assertIs<FieldReading.Unknown>(i5.feedNotification("00000000000e030000a5".bytes()).single().readings[DeviceField.PROMPT_VOLUME])

        val i7 = createProtocolCodec(ModelProfile.I7_RS_V2)
        // Synthetic independently worked LTV records and checksums, not captures.
        for ((rx, field, expected) in listOf(
            Triple("dd00020700e6aa", DeviceField.TOUCH_ENABLED, FieldValue.Toggle(true)),
            Triple("dd00020801e8aa", DeviceField.EAR_DETECTION, FieldValue.Toggle(false)),
            Triple("dd00022b000aaa", DeviceField.AUDIO_MODE, FieldValue.Text("aac_sbc")),
            Triple("dd00022b030daa", DeviceField.AUDIO_MODE, FieldValue.Text("lc3")),
            Triple("dd0002320011aa", DeviceField.MULTIPOINT, FieldValue.Toggle(false)),
            Triple("dd0002320112aa", DeviceField.MULTIPOINT, FieldValue.Toggle(true)),
        )) {
            val fields = i7.feedNotification(rx.bytes()).single().readings
            assertEquals(mapOf(field to expected), fields.mapValues { assertIs<FieldReading.Known>(it.value).value })
        }
        assertIs<FieldReading.Unknown>(i7.feedNotification("dd00020802e9aa".bytes()).single().readings[DeviceField.EAR_DETECTION])
        assertIs<FieldReading.Unknown>(i7.feedNotification("dd0002320213aa".bytes()).single().readings[DeviceField.MULTIPOINT])
    }

    @Test
    fun actualI5InitializationReadsRemainSeparateFromSettingValidation() {
        // Actual Linux initialization reads, 2026-10-18, firmware125; no setters were captured.
        // rose_reverse/analysis/i5_live_read_2026-10-18.jsonl:12,17,19,23,29.
        val codec = createProtocolCodec(ModelProfile.I5_ZT)
        for ((rx, field, expected) in listOf(
            Triple("09ff00000108010b00011e", DeviceField.AUDIO_MODE, FieldValue.Text("ldac")),
            Triple("09ff0000010a010b00001f", DeviceField.TOUCH_ENABLED, FieldValue.Toggle(true)),
            Triple("09ff0000010c010b000122", DeviceField.EAR_DETECTION, FieldValue.Toggle(false)),
            Triple("09ff0000010e030b000126", DeviceField.PROMPT_VOLUME, FieldValue.Number(2)),
        )) {
            assertEquals(expected, assertIs<FieldReading.Known>(codec.feedNotification(rx.bytes()).single().readings[field]).value)
        }
        val touch = codec.feedNotification("09ff00000104011200060502040605030746".bytes()).single()
        val bindings = assertIs<FieldValue.TouchKeys>(assertIs<FieldReading.Known>(touch.readings[DeviceField.TOUCH_KEYS]).value).bindings
        assertEquals("none", bindings.single { it.side == "left" && it.gesture == "single" }.action)
        assertEquals("noise_cycle", bindings.single { it.side == "right" && it.gesture == "long" }.action)
    }
}

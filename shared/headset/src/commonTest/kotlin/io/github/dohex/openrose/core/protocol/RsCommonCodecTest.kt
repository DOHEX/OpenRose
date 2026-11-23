package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.models.ModelProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RsCommonCodecTest {
    @Test
    fun initializationUsesDocumentedFixedFramesAndDelay() {
        val shared = "ff000ffa010708090c0d0e122a2b2c2d2e33aa"
        val buds = createProtocolCodec(ModelProfile.BUDSFREE_I_RS_COMMON).initQueries(20)
        assertEquals(listOf(shared, "ff0508fa2a090c0e07012baa"), buds.map { it.bytes.hex() })
        val open = createProtocolCodec(ModelProfile.OPENFEEL_LDAC_RS_COMMON).initQueries(20)
        assertEquals(listOf(shared, "ff0002fa2baa"), open.map { it.bytes.hex() })
        val cambrian = createProtocolCodec(ModelProfile.CAMBRIAN_RS_COMMON).initQueries(20)
        assertEquals(listOf(shared, shared), cambrian.map { it.bytes.hex() })
        assertEquals(500L, cambrian.last().delayBeforeMillis)
    }

    @Test
    fun writesUseLegacyCommandsAndIndependentReadbacks() {
        val codec = createProtocolCodec(ModelProfile.CAMBRIAN_RS_COMMON)
        for ((setting, value, expected) in listOf(
            Triple(DeviceSetting.NOISE_MODE, FieldValue.Text("anc"), "ff00021202aa"),
            Triple(DeviceSetting.EQ, FieldValue.Text("pop"), "ff00022a01aa"),
            Triple(DeviceSetting.TRANSPARENCY_LEVEL, FieldValue.Number(3), "ff00025a03aa"),
            Triple(DeviceSetting.MULTIPOINT, FieldValue.Toggle(true), "ff00022b04aa"),
            Triple(DeviceSetting.PROMPT_VOLUME, FieldValue.Number(5), "ff00025c05aa"),
        )) {
            assertEquals(expected, codec.prepareSetting(setting, value).writeFrames.single().hex())
        }
        val sport = createProtocolCodec(ModelProfile.SPORTFEEL_RS_COMMON)
        assertEquals("ff00023301aa", sport.prepareSetting(DeviceSetting.SPATIAL_AUDIO, FieldValue.Toggle(true)).writeFrames.single().hex())
        assertEquals(
            "ff0003010104aa",
            codec.prepareSetting(
                DeviceSetting.TOUCH_BINDING,
                FieldValue.TouchAssignment("left", "single", "volume_up"),
            ).writeFrames.single().hex(),
        )
    }

    @Test
    fun budsFreeTouchBindingsUseDocumentedSlotsAndActionCodes() {
        val codec = createProtocolCodec(ModelProfile.BUDSFREE_I_RS_COMMON)
        val profile = ModelProfile.BUDSFREE_I_RS_COMMON
        assertEquals((profile.spec.protocol as ModelProtocol.RsCommon).touchActions.keys, requireNotNull(profile.capabilities().touchControl).actions)
        assertEquals(
            "ff0003010101aa",
            codec.prepareSetting(
                DeviceSetting.TOUCH_BINDING,
                FieldValue.TouchAssignment("left", "single", "play_pause"),
            ).writeFrames.single().hex(),
        )
        assertEquals(
            "ff0003011101aa",
            codec.prepareSetting(
                DeviceSetting.TOUCH_BINDING,
                FieldValue.TouchAssignment("right", "single", "play_pause"),
            ).writeFrames.single().hex(),
        )
        for ((action, tx) in listOf(
            "none" to "ff0003010300aa",
            "play_pause" to "ff0003010301aa",
            "previous_track" to "ff0003010302aa",
            "next_track" to "ff0003010303aa",
            "volume_up" to "ff0003010305aa",
            "volume_down" to "ff0003010306aa",
        )) {
            val assignment = FieldValue.TouchAssignment("left", "triple", action)
            val setting = codec.prepareSetting(DeviceSetting.TOUCH_BINDING, assignment)
            assertEquals(tx, setting.writeFrames.single().hex())
            assertEquals(mapOf(DeviceField.TOUCH_KEYS to assignment), setting.expectedValues)
        }
        assertEquals(
            "ff0003011206aa",
            codec.prepareSetting(
                DeviceSetting.TOUCH_BINDING,
                FieldValue.TouchAssignment("right", "double", "volume_down"),
            ).writeFrames.single().hex(),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "long", "none"))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("right", "long", "none"))
        }
        for (action in listOf("voice_assistant", "noise_cycle", "game_mode")) {
            assertFailsWith<UnsupportedOperationException> {
                codec.prepareSetting(DeviceSetting.TOUCH_BINDING, FieldValue.TouchAssignment("left", "single", action))
            }
        }
    }

    @Test
    fun syntheticBudsFreeTouchReplyDecodesEveryBinding() {
        val reply = createProtocolCodec(ModelProfile.BUDSFREE_I_RS_COMMON)
            .feedNotification("ff00110100010002000300000000000500060000aa".bytes())
            .single()
        val keys = assertIs<FieldValue.TouchKeys>(
            assertIs<FieldReading.Known>(reply.readings[DeviceField.TOUCH_KEYS]).value,
        )
        assertEquals(
            listOf(
                TouchBinding("left", "single", "play_pause", 1),
                TouchBinding("left", "double", "previous_track", 2),
                TouchBinding("left", "triple", "next_track", 3),
                TouchBinding("right", "single", "volume_up", 5),
                TouchBinding("right", "double", "volume_down", 6),
                TouchBinding("right", "triple", "none", 0),
            ),
            keys.bindings,
        )
    }

    @Test
    fun hardwareBranchesRejectCommandsTheyCannotOffer() {
        val ldac = createProtocolCodec(ModelProfile.OPENFEEL_LDAC_RS_COMMON)
        val lhdc = createProtocolCodec(ModelProfile.OPENFEEL_LHDC_RS_COMMON)
        assertEquals("ff00022b01aa", ldac.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("ldac")).writeFrames.single().hex())
        assertEquals("ff00022b02aa", lhdc.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("lhdc")).writeFrames.single().hex())
        assertFailsWith<IllegalArgumentException> { ldac.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("lhdc")) }
        assertFailsWith<IllegalArgumentException> { lhdc.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("ldac")) }
        assertFailsWith<UnsupportedOperationException> {
            createProtocolCodec(ModelProfile.OPENFUN_RS_COMMON).prepareSetting(DeviceSetting.TOUCH_ENABLED, FieldValue.Toggle(true))
        }
        val touch = createProtocolCodec(ModelProfile.OPENFUN_TOUCH_RS_COMMON)
        assertEquals("ff00020e00aa", touch.prepareSetting(DeviceSetting.TOUCH_ENABLED, FieldValue.Toggle(true)).writeFrames.single().hex())
    }

    @Test
    fun syntheticRepliesDecodeAndBufferAcrossFragments() {
        val codec = createProtocolCodec(ModelProfile.OPENFEEL_DUAL_RS_COMMON)
        assertTrue(codec.feedNotification("ff00020e".bytes()).isEmpty())
        val reply = codec.feedNotification("01aa".bytes()).single()
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(reply.readings[DeviceField.GAME_MODE]).value)
        val multi = codec.feedNotification("ff00022b04aa".bytes()).single()
        assertEquals(FieldValue.Toggle(true), assertIs<FieldReading.Known>(multi.readings[DeviceField.MULTIPOINT]).value)
        val battery = codec.feedNotification("ff00040c32465aaa".bytes()).single()
        assertEquals(FieldValue.Number(90), assertIs<FieldReading.Known>(battery.readings[DeviceField.CASE_BATTERY]).value)
        val single = createProtocolCodec(ModelProfile.SWIFT_RS_COMMON).feedNotification("ff00040c32465aaa".bytes()).single()
        assertEquals(setOf(DeviceField.CASE_BATTERY), single.readings.keys)
    }
}

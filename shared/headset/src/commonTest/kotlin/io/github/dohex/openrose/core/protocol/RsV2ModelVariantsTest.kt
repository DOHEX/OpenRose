package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.models.ModelProtocol
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RsV2ModelVariantsTest {
    private fun firmware(vararg parts: Int) = FieldValue.Firmware(parts.toList(), parts.joinToString(""))

    @Test
    fun clockSyncUsesLocalCalendarBytesAndOnlyTheModelThatRequestsIt() {
        val date = LocalDateTime(2026, 11, 20, 17, 38, 9)
        val profile = ModelProfile.ASTRO_X_RS_V2
        val astro = RsCommonV2Codec(profile, profile.spec.protocol as ModelProtocol.RsCommonV2) { date }
        val commands = astro.initQueries(512)
        assertEquals(2, commands.size)
        // RoseLink setTime(2026-11-20 17:38:09), synthetic. The year is split into 20 and 26.
        assertEquals("ff010835141a0b14112609caaa", commands[1].bytes.hex())
        assertTrue(commands[1].responseKeys.isEmpty())
        assertEquals(1, createProtocolCodec(ModelProfile.KY01_RS_V2).initQueries(512).size)
    }

    @Test
    fun customCurveReadFollowsTheFirmwareGateAndConfirmationsUseBothTags() {
        val ceramic = createProtocolCodec(ModelProfile.CERAMICS_MK2_RS_V2)
        assertEquals(1, ceramic.initQueries(512).size)
        assertTrue(ceramic.firmwareInitQueries(firmware(1, 5, 3)).isEmpty())
        assertEquals("ff0102fa3e3aaa", ceramic.firmwareInitQueries(firmware(1, 5, 4)).single().bytes.hex())
        val curve = ceramic.prepareSetting(DeviceSetting.CUSTOM_EQ, FieldValue.IntegerList(List(10) { 0 }), firmware(1, 5, 4))
        assertEquals(listOf(setOf(DeviceField.EQ), setOf(DeviceField.CUSTOM_EQ)), curve.readbacks.map { it.expectedFields })
        val newerBranch = createProtocolCodec(ModelProfile.CERAMICS_MK2_5736_RS_V2)
        assertEquals("fa3e", newerBranch.initQueries(512).last().key)
        assertTrue(newerBranch.firmwareInitQueries(firmware(1, 5, 4)).isEmpty())
    }

    @Test
    fun firstAngelPresetBankRequiresConfirmedGameMode() {
        val profile = ModelProfile.FIRST_ANGEL_RS_V2
        assertTrue(profile.capabilities().equalizer!!.presets.isEmpty())
        assertEquals(setOf("hifi", "pop"), profile.capabilities(gameMode = false).equalizer!!.presets)
        assertEquals(setOf("game_1"), profile.capabilities(gameMode = true).equalizer!!.presets)
        val codec = createProtocolCodec(profile)
        fun confirmedGameMode(mode: Boolean?) = if (mode == null) {
            emptyMap()
        } else {
            mapOf(DeviceField.GAME_MODE to FieldReading.Known(FieldValue.Toggle(mode), if (mode) "01" else "00"))
        }
        for (mode in listOf(false, null)) {
            assertFailsWith<UnsupportedOperationException> {
                codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("game_1"), currentFields = confirmedGameMode(mode))
            }
        }
        assertEquals(
            "ff00022aa0cbaa",
            codec.prepareSetting(DeviceSetting.EQ, FieldValue.Text("game_1"), currentFields = confirmedGameMode(true)).writeFrames.single().hex(),
        )
        val reply = codec.feedNotification("dd00022aa0ccaa".bytes()).single() // synthetic records-only checksum
        assertEquals(FieldValue.Text("game_1"), (reply.readings[DeviceField.EQ] as FieldReading.Known).value)
    }

    @Test
    fun ieNeoCycleKeepsOnlyItsThreeChoicesAndEncodesTheUnusedWindByte() {
        val codec = createProtocolCodec(ModelProfile.EARFEEL_IE_NEO_RS_V2)
        val cycle = FieldValue.NoiseCycle(mapOf("anc" to true, "normal" to false, "transparency" to true))
        assertEquals("ff000536010000013caa", codec.prepareSetting(DeviceSetting.NOISE_CYCLE, cycle).writeFrames.single().hex())
        val reply = codec.feedNotification("dd000536010000013daa".bytes()).single() // synthetic records-only checksum
        assertEquals(cycle, (reply.readings[DeviceField.NOISE_CYCLE] as FieldReading.Known).value)
    }

    @Test
    fun aptxAndDeepAncAreOfferedOnlyOnModelsWhosePanelSupportsThem() {
        val speak = createProtocolCodec(ModelProfile.SPEAKFREE_RS_V2)
        assertEquals("ff00022b0632aa", speak.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("aptx_adaptive")).writeFrames.single().hex())
        val audioReply = speak.feedNotification("dd00022b0633aa".bytes()).single() // synthetic
        assertEquals(FieldValue.Text("aptx_adaptive"), (audioReply.readings[DeviceField.AUDIO_MODE] as FieldReading.Known).value)
        val furina = createProtocolCodec(ModelProfile.FURINA_RS_V2)
        assertEquals("ff0002090610aa", furina.prepareSetting(DeviceSetting.NOISE_MODE, FieldValue.Text("deep")).writeFrames.single().hex())
        assertFailsWith<UnsupportedOperationException> { furina.prepareSetting(DeviceSetting.AUDIO_MODE, FieldValue.Text("aptx_hd")) }
    }

    @Test
    fun overlappingRetailNamesNeverSelectTheOtherHardwareBranch() {
        for (name in listOf("ROSE CERAMICS MK2", "CERAMICS U", "ROSE SportFeel", "SpeakFeel")) {
            assertNull(ModelProfile.match(name), name)
        }
        assertEquals(ModelProfile.EARFEEL_IE_NEO_RS_V2, ModelProfile.match("EarFeel iE Neo"))
        assertEquals(ModelProfile.BUDSFEEL_AE_NEO_RS_V2, ModelProfile.match("BudsFeel AE Neo"))
        assertEquals(ModelProfile.OPENFEEL_L_RS_V2, ModelProfile.match("OpenFeel L"))
        assertEquals(ModelProfile.CAMBRIAN_S_RS_COMMON, ModelProfile.match("ROSE Cambrian S"))
        assertNotNull(ModelProfile.match("ROSE EARFEEL iE2"))
    }
}

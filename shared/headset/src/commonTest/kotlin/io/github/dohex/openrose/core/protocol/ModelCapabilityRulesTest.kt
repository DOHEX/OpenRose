package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCapabilityRulesTest {
    private fun firmware(vararg parts: Int) = FieldValue.Firmware(parts.toList(), parts.joinToString(""))

    @Test
    fun unreadFirmwareLeavesGatedPromptVolumeUnknownButStillReadable() {
        for (profile in listOf(
            ModelProfile.FIRST_ANGEL_RS_V2,
            ModelProfile.CERAMICS_U_RS_V2,
            ModelProfile.CERAMICS_MK2_RS_V2,
            ModelProfile.I7_RS_V2,
        )) {
            val unread = profile.capabilities()
            assertEquals(emptySet(), unread.promptVolumeLevels, "$profile")
            assertFalse(DeviceSetting.PROMPT_VOLUME in unread.writableSettings, "$profile")
            // The reading is how the level is learned, so it must not wait for the firmware.
            assertTrue(DeviceField.PROMPT_VOLUME in unread.readableFields, "$profile")
            assertEquals(unread, profile.capabilities(firmware(1, 2)), "$profile malformed firmware is also unread")
        }
        val old = ModelProfile.FIRST_ANGEL_RS_V2.capabilities(firmware(5, 0, 9))
        assertEquals((1..5).toSet(), old.promptVolumeLevels)
        assertTrue(DeviceSetting.PROMPT_VOLUME in old.writableSettings)
        assertEquals((0..5).toSet(), ModelProfile.FIRST_ANGEL_RS_V2.capabilities(firmware(5, 1, 0)).promptVolumeLevels)
    }

    @Test
    fun unreadFirmwareOffersNoFirmwareGatedControlsAndTheirReachingFirmwareDoes() {
        val unreadU = ModelProfile.CERAMICS_U_RS_V2.capabilities()
        assertNull(unreadU.equalizer?.custom)
        assertEquals(emptySet(), unreadU.gainLevels)
        val readU = ModelProfile.CERAMICS_U_RS_V2.capabilities(firmware(0, 0, 33))
        assertNotNull(readU.equalizer?.custom)
        assertEquals((0..2).toSet(), readU.gainLevels)
        assertNull(ModelProfile.CERAMICS_MK2_RS_V2.capabilities().equalizer?.custom)
        assertNotNull(ModelProfile.CERAMICS_MK2_RS_V2.capabilities(firmware(1, 5, 4)).equalizer?.custom)
        assertFalse(DeviceSetting.GAIN in ModelProfile.ASTRO_X_RS_V2.capabilities().writableSettings)
        assertTrue(DeviceSetting.GAIN in ModelProfile.ASTRO_X_RS_V2.capabilities(firmware(1, 5, 2)).writableSettings)
    }

    @Test
    fun aWritableSettingWithoutOptionsIsNeverWritable() {
        val firmwares = listOf(null, firmware(0, 0, 1), firmware(9, 9, 9))
        for (profile in ModelProfile.entries) {
            for (firmware in firmwares) {
                val capabilities = profile.capabilities(firmware)
                val writable = capabilities.writableSettings
                fun requireOptions(setting: DeviceSetting, options: Collection<*>?) {
                    if (setting in writable) assertTrue(options.orEmpty().isNotEmpty(), "$profile $firmware $setting")
                }
                requireOptions(DeviceSetting.NOISE_MODE, capabilities.noiseControl?.modes)
                requireOptions(DeviceSetting.NOISE_CYCLE, capabilities.noiseControl?.cycleModes)
                requireOptions(DeviceSetting.EQ, capabilities.equalizer?.presets)
                requireOptions(DeviceSetting.CUSTOM_EQ, capabilities.equalizer?.custom?.bandsHz)
                requireOptions(DeviceSetting.TOUCH_BINDING, capabilities.touchControl?.actions)
                requireOptions(DeviceSetting.AUDIO_MODE, capabilities.audioControl?.modes)
                requireOptions(DeviceSetting.PROMPT_VOLUME, capabilities.promptVolumeLevels)
                requireOptions(DeviceSetting.PROMPT_LANGUAGE, capabilities.promptLanguages)
                requireOptions(DeviceSetting.GAIN, capabilities.gainLevels)
                requireOptions(DeviceSetting.HEARING_PROTECTION, capabilities.hearingProtectionLevels)
                requireOptions(DeviceSetting.BASS_BOOST, capabilities.bassBoostLevels)
            }
        }
    }
}

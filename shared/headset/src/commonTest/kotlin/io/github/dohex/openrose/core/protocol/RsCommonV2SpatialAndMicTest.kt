package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RsCommonV2SpatialAndMicTest {
    @Test
    fun astroSpatialModeAndEarAdaptationStayConsistent() {
        val astro = ModelProfile.ASTRO_X_RS_V2
        val fixed = createProtocolCodec(astro).prepareSetting(DeviceSetting.SPATIAL_MODE, FieldValue.Text("fixed"))
        assertEquals(listOf("ff000238023baa", "ff01023a013daa"), fixed.writeFrames.map { it.hex() })
        assertEquals(listOf("ff0202fa3835aa", "ff0302fa3a38aa"), fixed.readbacks.map { it.bytes.hex() })
        assertEquals(
            mapOf(DeviceField.SPATIAL_MODE to FieldValue.Text("fixed"), DeviceField.EAR_ADAPTIVE to FieldValue.Toggle(true)),
            fixed.expectedValues,
        )
        val disable = createProtocolCodec(astro).prepareSetting(DeviceSetting.EAR_ADAPTIVE, FieldValue.Toggle(false))
        assertEquals(listOf("ff00023a003baa", "ff010238003aaa"), disable.writeFrames.map { it.hex() })
        val alreadyOn = mapOf(DeviceField.EAR_ADAPTIVE to FieldReading.Known(FieldValue.Toggle(true), "01"))
        val single = createProtocolCodec(astro).prepareSetting(DeviceSetting.SPATIAL_MODE, FieldValue.Text("fixed"), currentFields = alreadyOn)
        assertEquals(listOf("ff000238023baa"), single.writeFrames.map { it.hex() })
        val codec = createProtocolCodec(astro)
        val game = codec.prepareSetting(DeviceSetting.SPATIAL_CONTENT, FieldValue.Text("game"))
        assertEquals("ff00023d0341aa", game.writeFrames.single().hex())
        val reply = codec.feedNotification("dd00023801023d03023a0197aa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Text("head_tracking"), "01"), reply.readings[DeviceField.SPATIAL_MODE])
        assertEquals(FieldReading.Known(FieldValue.Text("game"), "03"), reply.readings[DeviceField.SPATIAL_CONTENT])
        assertEquals(FieldReading.Known(FieldValue.Toggle(true), "01"), reply.readings[DeviceField.EAR_ADAPTIVE])
    }

    @Test
    fun independentMicWritesUseSelectorsAndReadbacksUseTwoFlags() {
        val profile = ModelProfile.SPEAKFEEL2_RS_V2
        val activate = createProtocolCodec(profile).prepareSetting(DeviceSetting.INDEPENDENT_MIC_ACTIVATION, FieldValue.Toggle(true))
        assertEquals("ff00034900014caa", activate.writeFrames.single().hex())
        assertEquals(setOf(DeviceField.INDEPENDENT_MIC_ACTIVATION), activate.readback.expectedFields)
        val codec = createProtocolCodec(profile)
        val mode = codec.prepareSetting(DeviceSetting.INDEPENDENT_MIC_MODE, FieldValue.Toggle(true))
        assertEquals("ff00034901014daa", mode.writeFrames.single().hex())
        assertEquals(setOf(DeviceField.INDEPENDENT_MIC_MODE), mode.readback.expectedFields)
        val active = codec.feedNotification("dd00034901012baa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Toggle(true), "0101"), active.readings[DeviceField.INDEPENDENT_MIC_MODE])
        val inactive = codec.feedNotification("dd00034900012aaa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Toggle(false), "0001"), inactive.readings[DeviceField.INDEPENDENT_MIC_MODE])
        assertFalse(codec.feedNotification("dd00034902012caa".bytes()).single().accepted)
        assertFalse(codec.feedNotification("dd000549123456783faa".bytes()).single().accepted)
    }

    @Test
    fun newRoseLinkLabelsKeepTheSameEqualizerWireValues() {
        val moka = ModelProfile.MOKA_RS_V2
        assertEquals(setOf("pop", "hifi", "rock"), moka.capabilities().equalizer?.presets.orEmpty().toSet())
        val openFeel = ModelProfile.OPENFEEL_L_RS_V2
        assertEquals(mapOf("pop" to "BASS", "hifi" to "BALANCE"), openFeel.spec.equalizerPresetLabels)
        assertEquals("ff00022a012caa", createProtocolCodec(openFeel).prepareSetting(DeviceSetting.EQ, FieldValue.Text("pop")).writeFrames.single().hex())
        assertTrue(DeviceSetting.SPATIAL_AUDIO !in ModelProfile.ASTRO_X_RS_V2.capabilities().writableSettings)
    }
}

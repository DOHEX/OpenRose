package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.transport.DeviceId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WritableEqualizerTest {
    private val curve = CustomEqualizerCapabilities(persistentListOf(100, 1_000, 10_000), minGain = -6, maxGain = 6)

    private fun device(writable: Set<DeviceSetting>, custom: CustomEqualizerCapabilities? = curve) = DeviceState(
        deviceId = DeviceId("eq"),
        sessionId = 1L,
        profile = ModelProfile.I7_RS_V2,
        capabilities = DeviceCapabilities(
            readableFields = persistentSetOf(DeviceField.EQ),
            writableSettings = writable.toPersistentSet(),
            equalizer = EqualizerCapabilities(persistentSetOf("hifi", "custom"), custom),
        ),
        fields = persistentMapOf(),
    )

    @Test
    fun theCustomPresetIsOfferedAsAPresetOnlyWhenNoCurveEditorIsWritable() {
        val withEditor = device(setOf(DeviceSetting.EQ, DeviceSetting.CUSTOM_EQ))
        assertEquals(curve, withEditor.writableCustomCurve)
        assertEquals(setOf("hifi"), withEditor.writablePresets)

        val presetsOnly = device(setOf(DeviceSetting.EQ))
        assertNull(presetsOnly.writableCustomCurve)
        assertEquals(setOf("hifi", "custom"), presetsOnly.writablePresets)
    }

    @Test
    fun nothingIsWritableWithoutTheMatchingPermissionOrWithInvertedGainBounds() {
        val curveOnly = device(setOf(DeviceSetting.CUSTOM_EQ))
        assertEquals(curve, curveOnly.writableCustomCurve)
        assertEquals(emptySet(), curveOnly.writablePresets)

        val inverted = device(setOf(DeviceSetting.EQ, DeviceSetting.CUSTOM_EQ), curve.copy(minGain = 6, maxGain = -6))
        assertNull(inverted.writableCustomCurve)
        assertEquals(setOf("hifi", "custom"), inverted.writablePresets)
    }
}

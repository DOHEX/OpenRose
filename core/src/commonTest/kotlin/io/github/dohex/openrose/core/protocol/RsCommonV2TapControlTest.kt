package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RsCommonV2TapControlTest {
    @Test
    fun tapToggleAndAssignmentQueryTheirOwnFields() {
        val profile = ModelProfile.ASTRO_X_RS_V2
        val switch = createProtocolCodec(profile).prepareSetting(DeviceSetting.TAP_ENABLED, FieldValue.Toggle(true))
        assertEquals("ff00023b013daa", switch.writeFrames.single().hex())
        assertEquals("ff0102fa3b37aa", switch.readbacks.single().bytes.hex())
        assertEquals(mapOf(DeviceField.TAP_ENABLED to FieldValue.Toggle(true)), switch.expectedValues)
        val binding = FieldValue.TouchAssignment("right", "triple", "voice_assistant")
        val codec = createProtocolCodec(profile)
        assertFailsWith<IllegalArgumentException> {
            codec.prepareSetting(DeviceSetting.TAP_BINDING, binding.copy(gesture = "single"))
        }
        assertFailsWith<UnsupportedOperationException> {
            codec.prepareSetting(DeviceSetting.TAP_BINDING, binding.copy(action = "noise_cycle"))
        }
        val write = codec.prepareSetting(DeviceSetting.TAP_BINDING, binding)
        assertEquals("ff00033c130758aa", write.writeFrames.single().hex())
        assertEquals("ff0102fa3c38aa", write.readbacks.single().bytes.hex())
        assertEquals(mapOf(DeviceField.TAP_KEYS to binding), write.expectedValues)
        assertFalse(DeviceSetting.TAP_ENABLED in ModelProfile.I7_RS_V2.capabilities().writableSettings)
        assertFailsWith<UnsupportedOperationException> {
            createProtocolCodec(ModelProfile.I7_RS_V2).prepareSetting(DeviceSetting.TAP_BINDING, binding)
        }
    }

    @Test
    fun tapNotificationsRequireAllFourSlotsAndPreserveUnknownActions() {
        val codec = createProtocolCodec(ModelProfile.ASTRO_X_RS_V2)
        // Synthetic LTV notifications. The binding pairs may arrive in any order.
        val enabled = codec.feedNotification("dd00023b011baa".bytes()).single()
        assertEquals(FieldReading.Known(FieldValue.Toggle(true), "01"), enabled.readings[DeviceField.TAP_ENABLED])
        val complete = codec.feedNotification("dd00093c13070201030212045aaa".bytes()).single()
        val keys = (complete.readings.getValue(DeviceField.TAP_KEYS) as FieldReading.Known).value as FieldValue.TouchKeys
        assertEquals(listOf("play_pause", "previous_track", "volume_up", "voice_assistant"), keys.bindings.map { it.action })
        assertNull(complete.readings[DeviceField.TOUCH_KEYS])
        val incomplete = codec.feedNotification("dd00073c0201030212043eaa".bytes()).single()
        assertFalse(DeviceField.TAP_KEYS in incomplete.readings)
        val unexpected = codec.feedNotification("dd00093c020803021204130761aa".bytes()).single()
        val unknown = (unexpected.readings.getValue(DeviceField.TAP_KEYS) as FieldReading.Known).value as FieldValue.TouchKeys
        assertEquals(TouchBinding("left", "double", null, 8), unknown.bindings.first())
    }
}

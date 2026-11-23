package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RsCommon386ProfilesTest {
    @Test
    fun cambrianSRepeatsTheBatchAfterHalfASecondAndOpenLinkReadsItOnce() {
        val frame = "ff000ffa010708090c0d0e122a2b2c2d2e33aa"
        val cambrian = createProtocolCodec(ModelProfile.CAMBRIAN_S_RS_COMMON).initQueries(20)
        val openLink = createProtocolCodec(ModelProfile.OPENLINK_RS_COMMON).initQueries(20)
        assertEquals(listOf(0L, 500L), cambrian.map { it.delayBeforeMillis })
        assertEquals(listOf(frame, frame), cambrian.map { it.bytes.hex() })
        assertEquals(listOf(frame), openLink.map { it.bytes.hex() })
    }

    @Test
    fun openLinkPublishesTwoBatteriesAndCambrianSUsesTheSingleUnitSlot() {
        val cambrian = ModelProfile.CAMBRIAN_S_RS_COMMON
        val openLink = ModelProfile.OPENLINK_RS_COMMON
        assertEquals(BatteryLayout.SINGLE_UNIT, cambrian.batteryLayout)
        assertFalse(DeviceField.LEFT_BATTERY in cambrian.capabilities().readableFields)
        assertTrue(DeviceSetting.TRANSPARENCY_LEVEL in cambrian.capabilities().writableSettings)
        assertEquals(BatteryLayout.EARBUDS_ONLY, openLink.batteryLayout)
        assertFalse(DeviceField.CASE_BATTERY in openLink.capabilities().readableFields)
        assertFalse(DeviceSetting.NOISE_MODE in openLink.capabilities().writableSettings)
        assertTrue(DeviceSetting.TOUCH_ENABLED in openLink.capabilities().writableSettings)
    }
}

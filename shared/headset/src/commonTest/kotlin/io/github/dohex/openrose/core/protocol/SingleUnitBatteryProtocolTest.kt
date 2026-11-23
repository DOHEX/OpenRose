package io.github.dohex.openrose.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SingleUnitBatteryProtocolTest {
    @Test
    fun unitBatteryDoesNotRequireAnEarbudInTheCaseAndKeepsMissingValuesDistinct() {
        val codec = createProtocolCodec(ModelProfile.SWIFT2_RS_V2)
        val battery = codec.feedNotification("dd00040cffff503baa".bytes()).single()
        assertEquals(FieldValue.Number(80), assertIs<FieldReading.Known>(battery.readings[DeviceField.CASE_BATTERY]).value)
        assertIs<FieldReading.NotProvided>(battery.readings[DeviceField.LEFT_BATTERY])
        assertIs<FieldReading.NotProvided>(battery.readings[DeviceField.RIGHT_BATTERY])
        assertIs<FieldReading.NotProvided>(codec.feedNotification("dd00040cffffffeaaa".bytes()).single().readings[DeviceField.CASE_BATTERY])
        assertIs<FieldReading.Unknown>(codec.feedNotification("dd00040cffff00ebaa".bytes()).single().readings[DeviceField.CASE_BATTERY])
        assertIs<FieldReading.Unknown>(createProtocolCodec(ModelProfile.I7_RS_V2).feedNotification("dd00040cffff503baa".bytes()).single().readings[DeviceField.CASE_BATTERY])
    }
}

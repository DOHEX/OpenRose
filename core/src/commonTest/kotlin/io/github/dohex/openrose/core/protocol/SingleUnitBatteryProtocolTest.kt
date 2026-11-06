package io.github.dohex.openrose.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SingleUnitBatteryProtocolTest {
    @Test
    fun unitBatteryDoesNotRequireAnEarbudInTheCaseAndKeepsMissingValuesDistinct() {
        val codec = createProtocolCodec(ModelProfile.SWIFT2_RS_V2)
        // 合成：左右槽 FF，charge 槽 50(80%)；Swift 2 的 charge 是单体电量。
        val battery = codec.feedNotification("dd00040cffff503baa".bytes()).single()
        assertEquals(FieldValue.Number(80), assertIs<FieldReading.Known>(battery.readings[DeviceField.CASE_BATTERY]).value)
        assertIs<FieldReading.NotProvided>(battery.readings[DeviceField.LEFT_BATTERY])
        assertIs<FieldReading.NotProvided>(battery.readings[DeviceField.RIGHT_BATTERY])
        // 合成：FF 是未提供，00 是未知；它们都不能变成 0%。
        assertIs<FieldReading.NotProvided>(codec.feedNotification("dd00040cffffffeaaa".bytes()).single().readings[DeviceField.CASE_BATTERY])
        assertIs<FieldReading.Unknown>(codec.feedNotification("dd00040cffff00ebaa".bytes()).single().readings[DeviceField.CASE_BATTERY])
        // 同一报文的旧双耳语义：没有任意耳机的在盒证据，不发布仓电量。
        assertIs<FieldReading.Unknown>(createProtocolCodec(ModelProfile.I7_RS_V2).feedNotification("dd00040cffff503baa".bytes()).single().readings[DeviceField.CASE_BATTERY])
    }
}

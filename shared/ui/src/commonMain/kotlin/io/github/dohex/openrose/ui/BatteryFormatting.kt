package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ModelProfile

internal fun ModelProfile.batteryReadings(): List<Pair<String, DeviceField>> = batteryLayout.batterySlots()

internal fun BatteryLayout.batterySlots(): List<Pair<String, DeviceField>> = when (this) {
    BatteryLayout.EARBUDS_AND_CASE -> listOf(
        "左耳" to DeviceField.LEFT_BATTERY,
        "右耳" to DeviceField.RIGHT_BATTERY,
        "充电盒" to DeviceField.CASE_BATTERY,
    )

    BatteryLayout.EARBUDS_ONLY -> listOf("左耳" to DeviceField.LEFT_BATTERY, "右耳" to DeviceField.RIGHT_BATTERY)

    BatteryLayout.SINGLE_UNIT -> listOf("电量" to DeviceField.CASE_BATTERY)
}

internal fun BatteryLayout.compactBatterySlots(): List<Pair<String, DeviceField>> = when (this) {
    BatteryLayout.EARBUDS_AND_CASE -> listOf(
        "L" to DeviceField.LEFT_BATTERY,
        "R" to DeviceField.RIGHT_BATTERY,
        "C" to DeviceField.CASE_BATTERY,
    )

    BatteryLayout.EARBUDS_ONLY -> listOf("L" to DeviceField.LEFT_BATTERY, "R" to DeviceField.RIGHT_BATTERY)

    BatteryLayout.SINGLE_UNIT -> listOf("电量" to DeviceField.CASE_BATTERY)
}

internal fun batterySummary(
    slots: List<Pair<String, DeviceField>>,
    showUnknown: Boolean,
    unknownText: String = "未知",
    percent: (DeviceField) -> Int?,
): String? = slots.mapNotNull { (label, field) ->
    percent(field)?.let { "$label $it%" } ?: "$label $unknownText".takeIf { showUnknown }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

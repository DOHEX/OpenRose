package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.ModelProfile

/** Battery readings with their labels, in display order; a single unit reports in the case slot. */
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

/**
 * One line of battery text such as "左耳 80% · 右耳 75%". [percent] gives a slot's 0..100 reading;
 * a slot without one shows "未知" when [showUnknown], otherwise it is left out. Null when nothing shows.
 */
internal fun batterySummary(
    slots: List<Pair<String, DeviceField>>,
    showUnknown: Boolean,
    percent: (DeviceField) -> Int?,
): String? = slots.mapNotNull { (label, field) ->
    percent(field)?.takeIf { it in 0..100 }?.let { "$label $it%" } ?: "$label 未知".takeIf { showUnknown }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

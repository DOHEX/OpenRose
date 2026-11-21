package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.toPersistentSet

internal val rsCommonTouchSlots = listOf("left", "right").flatMap { side ->
    listOf("single", "double", "triple").map { TouchSlot(side, it) }
}.toPersistentSet()

internal fun rsCommonClassicAddress(hex: String, start: Int): String? {
    if (hex.length < start + 12) return null
    val address = hex.substring(start, start + 12).uppercase()
    if (address.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
    if (address == "000000000000" || address == "FFFFFFFFFFFF") return null
    return address.chunked(2).joinToString(":")
}

/** RS battery slots follow the hardware layout. Other fields must have evidence for the model. */
internal fun rsReadings(layout: BatteryLayout, vararg fields: DeviceField): PersistentSet<DeviceField> = buildSet {
    add(DeviceField.FIRMWARE)
    add(DeviceField.GAME_MODE)
    if (layout != BatteryLayout.EARBUDS_ONLY) add(DeviceField.CASE_BATTERY)
    if (layout != BatteryLayout.SINGLE_UNIT) {
        add(DeviceField.LEFT_BATTERY)
        add(DeviceField.RIGHT_BATTERY)
        add(DeviceField.LEFT_IN_CASE)
        add(DeviceField.RIGHT_IN_CASE)
    }
    addAll(fields)
}.toPersistentSet()

internal val openFeelReadings = rsReadings(
    BatteryLayout.EARBUDS_AND_CASE,
    DeviceField.TOUCH_KEYS,
    DeviceField.TOUCH_ENABLED,
    DeviceField.EQ,
    DeviceField.AUDIO_MODE,
    DeviceField.MULTIPOINT,
)

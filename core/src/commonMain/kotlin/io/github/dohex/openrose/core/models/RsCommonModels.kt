package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.TouchSlot
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

package io.github.dohex.openrose.core.models

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

internal fun f8Address(raw: String): String? {
    if (raw.length < 22 || raw.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    val octets = raw.substring(10, 22).uppercase().chunked(2)
    if (octets.all { it == "00" } || octets.all { it == "FF" }) return null
    return octets.joinToString(":")
}

internal val f8ThreeGestureSlots = earbudTouchSlots.filter { it.gesture != "triple" }.toPersistentSet()
internal val f8Actions = persistentSetOf(
    "none",
    "voice_assistant",
    "previous_track",
    "next_track",
    "volume_up",
    "volume_down",
    "play_pause",
    "noise_cycle",
)
internal val f8ActionsWithoutNoise = f8Actions.removing("noise_cycle")

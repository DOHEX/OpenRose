package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.DeviceField
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

/** Fields decoded by the basic F8 receiver; these earbuds do not report a case slot. */
internal val f8BasicReadings = persistentSetOf(
    DeviceField.LEFT_BATTERY,
    DeviceField.RIGHT_BATTERY,
    DeviceField.LEFT_CHARGING,
    DeviceField.RIGHT_CHARGING,
    DeviceField.FIRMWARE,
    DeviceField.EQ,
    DeviceField.TOUCH_KEYS,
    DeviceField.GAME_MODE,
)

internal val f8ExtendedReadings = f8BasicReadings.addingAll(
    listOf(
        DeviceField.CASE_BATTERY,
        DeviceField.EAR_DETECTION,
        DeviceField.PROMPT_LANGUAGE,
        DeviceField.AUDIO_MODE,
        DeviceField.MULTIPOINT,
        DeviceField.NOISE_MODE,
        DeviceField.PROMPT_VOLUME,
    ),
)

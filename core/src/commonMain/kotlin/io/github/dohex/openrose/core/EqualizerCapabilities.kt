package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile

data class EqualizerCapabilities(val presets: Set<String>)

fun equalizerCapabilities(profile: ModelProfile, firmware: FieldValue.Firmware? = null): EqualizerCapabilities {
    val presets = setOf("pop", "hifi", "rock", "light")
    // RoseLink compares decimal text formed by concatenating the first three unsigned bytes.
    // Validate the source parts instead of trusting a caller-supplied display label.
    val version = firmware?.parts?.takeIf { it.size == 3 && it.all { part -> part in 0..255 } }
        ?.joinToString("")?.toIntOrNull()
    return EqualizerCapabilities(
        if (profile == ModelProfile.I7_RS_V2 && version != null && version >= 152) presets + "eap" else presets,
    )
}

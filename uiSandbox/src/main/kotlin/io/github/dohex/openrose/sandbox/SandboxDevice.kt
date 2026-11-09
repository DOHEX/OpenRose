package io.github.dohex.openrose.sandbox

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldReading
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.ModelProfile
import io.github.dohex.openrose.core.protocol.TouchBinding
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap

/** Every noise cycle a codec reads or writes carries these four modes. */
private val noiseCycleModes = listOf("normal", "transparency", "wind", "anc")

/** The case reads this much below the earbuds, as it usually does after charging them. */
private const val CASE_BATTERY_OFFSET = 20

/**
 * What a connected device of [profile] would report for each field it can read, chosen among the options its
 * capabilities offer. A field with nothing to offer reads as not provided, as a device that omits it would.
 */
internal fun sandboxFields(
    profile: ModelProfile,
    capabilities: DeviceCapabilities,
    firmware: FieldValue.Firmware?,
    battery: SandboxBattery,
): PersistentMap<DeviceField, FieldReading> = capabilities.readableFields.associateWith { field ->
    when (field) {
        DeviceField.LEFT_BATTERY, DeviceField.RIGHT_BATTERY, DeviceField.CASE_BATTERY ->
            batteryReading(field, profile.batteryLayout, battery)

        DeviceField.FIRMWARE -> firmware?.let(::known) ?: FieldReading.Loading

        else -> fieldValue(field, capabilities)?.let(::known) ?: FieldReading.NotProvided("")
    }
}.toPersistentMap()

/** Battery follows the model's layout: a single unit reports in the case slot, and earbuds-only models have no case. */
internal fun batteryReading(field: DeviceField, layout: BatteryLayout, battery: SandboxBattery): FieldReading {
    val percent = when (field) {
        DeviceField.CASE_BATTERY -> when (layout) {
            BatteryLayout.EARBUDS_AND_CASE -> battery.percent?.let { (it - CASE_BATTERY_OFFSET).coerceAtLeast(1) }
            BatteryLayout.SINGLE_UNIT -> battery.percent
            BatteryLayout.EARBUDS_ONLY -> null
        }

        else -> battery.percent.takeIf { layout != BatteryLayout.SINGLE_UNIT }
    }
    return percent?.let { known(FieldValue.Number(it)) } ?: FieldReading.NotProvided("ff")
}

/** A field with nothing to offer, or one only an event fills, has no value here. */
private fun fieldValue(field: DeviceField, capabilities: DeviceCapabilities): FieldValue? = when (field) {
    DeviceField.NOISE_MODE, DeviceField.ANC_LEVEL, DeviceField.TRANSPARENCY_LEVEL, DeviceField.NOISE_CYCLE ->
        capabilities.noiseControl?.let { noiseValue(field, it) }

    DeviceField.EQ -> capabilities.equalizer?.presets?.preferring("pop")?.let(FieldValue::Text)

    DeviceField.CUSTOM_EQ -> capabilities.equalizer?.custom?.let { FieldValue.IntegerList(List(it.bandsHz.size) { 0 }) }

    DeviceField.TOUCH_KEYS -> capabilities.touchControl?.let(::touchKeys)

    DeviceField.MULTIPOINT, DeviceField.TOUCH_ENABLED, DeviceField.EAR_DETECTION -> FieldValue.Toggle(true)

    DeviceField.GAME_MODE, DeviceField.LEFT_CHARGING, DeviceField.RIGHT_CHARGING, DeviceField.LEFT_IN_CASE,
    DeviceField.RIGHT_IN_CASE, DeviceField.CASE_FLAG,
    -> FieldValue.Toggle(false)

    else -> choiceValue(field, capabilities)
}

private fun noiseValue(field: DeviceField, noise: NoiseControlCapabilities): FieldValue? = when (field) {
    DeviceField.NOISE_MODE -> noise.modes.preferring("anc")?.let(FieldValue::Text)
    DeviceField.ANC_LEVEL -> noise.ancLevel?.levels?.middle()?.let(FieldValue::Number)
    DeviceField.TRANSPARENCY_LEVEL -> noise.transparencyLevel?.levels?.middle()?.let(FieldValue::Number)
    else -> FieldValue.NoiseCycle(noiseCycleModes.associateWith { it in noise.cycleModes })
}

/**
 * One of the options the model offers for a setting chosen from a list. A bass level reads only together with the
 * custom-slot EQ reply and a find event only when one happens, so neither has a value until then.
 */
private fun choiceValue(field: DeviceField, capabilities: DeviceCapabilities): FieldValue? = when (field) {
    DeviceField.AUDIO_MODE -> capabilities.audioControl?.modes?.preferring("aac_sbc")?.let(FieldValue::Text)
    DeviceField.PROMPT_VOLUME -> capabilities.promptVolumeLevels.middle()?.let(FieldValue::Number)
    DeviceField.GAIN -> capabilities.gainLevels.middle()?.let(FieldValue::Number)
    DeviceField.HEARING_PROTECTION -> capabilities.hearingProtectionLevels.minOrNull()?.let(FieldValue::Number)
    DeviceField.PROMPT_LANGUAGE -> capabilities.promptLanguages.preferring("chinese")?.let(FieldValue::Text)
    else -> null
}

private fun touchKeys(touch: TouchControlCapabilities): FieldValue.TouchKeys {
    val actions = touch.actions.sorted()
    return FieldValue.TouchKeys(
        touch.slots.sortedWith(compareBy({ it.side }, { it.gesture })).map { slot ->
            val action = actions.firstOrNull { it == defaultTouchAction[slot.gesture] } ?: actions.firstOrNull()
            TouchBinding(slot.side, slot.gesture, action, actions.indexOf(action))
        },
    )
}

private val defaultTouchAction = mapOf(
    "single" to "play_pause",
    "double" to "next_track",
    "triple" to "previous_track",
    "long" to "noise_cycle",
)

private fun known(value: FieldValue): FieldReading = FieldReading.Known(value, "")

private fun Set<String>.preferring(preferred: String): String? = if (preferred in this) preferred else sorted().firstOrNull()

private fun Set<Int>.middle(): Int? = sorted().let { it.getOrNull(it.size / 2) }

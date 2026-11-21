package io.github.dohex.openrose.androidintegration.wire

import io.github.dohex.openrose.core.AudioControlCapabilities
import io.github.dohex.openrose.core.CustomEqualizerCapabilities
import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.EqualizerCapabilities
import io.github.dohex.openrose.core.NoiseControlCapabilities
import io.github.dohex.openrose.core.NoiseLevelCapabilities
import io.github.dohex.openrose.core.TouchControlCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.DeviceField
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

internal fun encode(capabilities: DeviceCapabilities): Wire = obj(
    "readable" to arr(capabilities.readableFields.map { Wire.Str(it.name) }),
    "writable" to arr(capabilities.writableSettings.map { Wire.Str(it.name) }),
    "noise" to capabilities.noiseControl?.let(::encode),
    "equalizer" to capabilities.equalizer?.let(::encode),
    "touch" to capabilities.touchControl?.let(::encodeTouch),
    "tap" to capabilities.tapControl?.let(::encodeTouch),
    "audio" to capabilities.audioControl?.let { audio ->
        obj("modes" to arr(audio.modes.map(::str)), "coupled" to bool(audio.multipointCoupled))
    },
    "promptVolume" to arr(capabilities.promptVolumeLevels.map(::num)),
    "gain" to arr(capabilities.gainLevels.map(::num)),
    "hearingProtection" to arr(capabilities.hearingProtectionLevels.map(::num)),
    "bassBoost" to arr(capabilities.bassBoostLevels.map(::num)),
    "promptLanguages" to arr(capabilities.promptLanguages.map(::str)),
    "spatialModes" to arr(capabilities.spatialModes.map(::str)),
    "spatialContents" to arr(capabilities.spatialContents.map(::str)),
)

internal fun decodeCapabilities(wire: Wire): DeviceCapabilities = wire.asObj().let { node ->
    DeviceCapabilities(
        readableFields = node.arr("readable").map { it.asEnum<DeviceField>() }.toPersistentSet(),
        writableSettings = node.arr("writable").map { it.asEnum<DeviceSetting>() }.toPersistentSet(),
        noiseControl = node.optional("noise")?.let(::decodeNoise),
        equalizer = node.optional("equalizer")?.let(::decodeEqualizer),
        touchControl = node.optional("touch")?.let(::decodeTouch),
        tapControl = node.optional("tap")?.let(::decodeTouch),
        audioControl = node.optional("audio")?.asObj()?.let { audio ->
            AudioControlCapabilities(audio.arr("modes").map { it.asStr() }.toPersistentSet(), audio.bool("coupled"))
        },
        promptVolumeLevels = node.arr("promptVolume").map { it.asInt() }.toPersistentSet(),
        gainLevels = node.arr("gain").map { it.asInt() }.toPersistentSet(),
        hearingProtectionLevels = node.arr("hearingProtection").map { it.asInt() }.toPersistentSet(),
        bassBoostLevels = node.arr("bassBoost").map { it.asInt() }.toPersistentSet(),
        promptLanguages = node.arr("promptLanguages").map { it.asStr() }.toPersistentSet(),
        spatialModes = node.optional("spatialModes")?.asArr().orEmpty().map { it.asStr() }.toPersistentSet(),
        spatialContents = node.optional("spatialContents")?.asArr().orEmpty().map { it.asStr() }.toPersistentSet(),
    )
}

private fun encodeTouch(touch: TouchControlCapabilities): Wire = obj(
    "slots" to arr(touch.slots.map { obj("side" to str(it.side), "gesture" to str(it.gesture)) }),
    "actions" to arr(touch.actions.map(::str)),
    "slotLabels" to arr(
        touch.slotLabels.map { (slot, label) ->
            obj("side" to str(slot.side), "gesture" to str(slot.gesture), "label" to str(label))
        },
    ),
)

private fun decodeTouch(wire: Wire): TouchControlCapabilities = wire.asObj().let { touch ->
    TouchControlCapabilities(
        touch.arr("slots")
            .map { it.asObj().let { slot -> TouchSlot(slot.str("side"), slot.str("gesture")) } }
            .toPersistentSet(),
        touch.arr("actions").map { it.asStr() }.toPersistentSet(),
        touch.arr("slotLabels").associate { entry ->
            entry.asObj().let { slot -> TouchSlot(slot.str("side"), slot.str("gesture")) to slot.str("label") }
        }.toPersistentMap(),
    )
}

private fun encode(noise: NoiseControlCapabilities): Wire = obj(
    "modes" to arr(noise.modes.map(::str)),
    "cycle" to arr(noise.cycleModes.map(::str)),
    "anc" to noise.ancLevel?.let(::encode),
    "transparency" to noise.transparencyLevel?.let(::encode),
    "minimumCycle" to num(noise.minimumCycleSelections),
)

private fun encode(levels: NoiseLevelCapabilities): Wire = obj(
    "levels" to arr(levels.levels.map(::num)),
    "modes" to arr(levels.visibleModes.map(::str)),
)

private fun decodeLevels(wire: Wire): NoiseLevelCapabilities = wire.asObj().let {
    NoiseLevelCapabilities(
        it.arr("levels").map { level -> level.asInt() }.toPersistentSet(),
        it.arr("modes").map { mode -> mode.asStr() }.toPersistentSet(),
    )
}

private fun decodeNoise(wire: Wire): NoiseControlCapabilities = wire.asObj().let {
    NoiseControlCapabilities(
        modes = it.arr("modes").map { mode -> mode.asStr() }.toPersistentSet(),
        cycleModes = it.arr("cycle").map { mode -> mode.asStr() }.toPersistentSet(),
        ancLevel = it.optional("anc")?.let(::decodeLevels),
        transparencyLevel = it.optional("transparency")?.let(::decodeLevels),
        minimumCycleSelections = it.int("minimumCycle"),
    )
}

private fun encode(equalizer: EqualizerCapabilities): Wire = obj(
    "presets" to arr(equalizer.presets.map(::str)),
    "custom" to equalizer.custom?.let {
        obj("bands" to arr(it.bandsHz.map(::num)), "min" to num(it.minGain), "max" to num(it.maxGain))
    },
)

private fun decodeEqualizer(wire: Wire): EqualizerCapabilities = wire.asObj().let { node ->
    EqualizerCapabilities(
        node.arr("presets").map { it.asStr() }.toPersistentSet(),
        node.optional("custom")?.asObj()?.let { custom ->
            CustomEqualizerCapabilities(custom.arr("bands").map { it.asInt() }.toPersistentList(), custom.int("min"), custom.int("max"))
        },
    )
}

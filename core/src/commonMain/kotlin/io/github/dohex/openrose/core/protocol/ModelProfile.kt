package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.models.ModelSpec
import io.github.dohex.openrose.core.models.ROSELINK_PREFIX_LENGTHS
import io.github.dohex.openrose.core.models.budsFeelMk2
import io.github.dohex.openrose.core.models.earFeelI7
import io.github.dohex.openrose.core.models.earFreeI5
import io.github.dohex.openrose.core.models.jasmine
import io.github.dohex.openrose.core.models.openFeelMk2
import io.github.dohex.openrose.core.models.swift2

data class GattProfile(val serviceUuid: String, val writeUuid: String, val notifyUuid: String)

/** A control-frame family. Its codec owns framing and field semantics; each model keeps its own capabilities. */
enum class ProtocolFamily(val displayName: String) {
    ZT("ZT"),
    RS_COMMON_V2("RS Common V2"),
    ;

    /** Fields this family's reader understands; this is not a list of enabled setting operations. */
    val readableFields: Set<DeviceField>
        get() = when (this) {
            ZT -> ztFields.values.flatten().toSet()
            RS_COMMON_V2 -> rsCommonV2Fields.values.flatten().toSet()
        }

    /** Prompt volume levels this family's frames can carry, before any model or firmware limit. */
    internal val promptVolumeLevels: IntRange
        get() = when (this) {
            ZT -> 1..5
            RS_COMMON_V2 -> 0..5
        }
}

/** How a model reports battery. */
enum class BatteryLayout {
    /** Left earbud, right earbud and charging case. */
    EARBUDS_AND_CASE,

    /** Left and right earbuds, with no charging case display. */
    EARBUDS_ONLY,

    /** One unit, which the protocol reports in its case slot. */
    SINGLE_UNIT,
}

/**
 * One 型号配置. Its name crosses processes and is persisted, so an entry is never renamed. Everything that differs
 * between models lives in the entry's [ModelSpec]; an entry without one does not compile.
 */
enum class ModelProfile {
    I5_ZT,
    I7_RS_V2,
    BUDSFEEL_MK2_RS_V2,
    OPENFEEL_MK2_RS_V2,
    SWIFT2_RS_V2,
    JASMINE_RS_V2,
    ;

    internal val spec: ModelSpec
        get() = when (this) {
            I5_ZT -> earFreeI5
            I7_RS_V2 -> earFeelI7
            BUDSFEEL_MK2_RS_V2 -> budsFeelMk2
            OPENFEEL_MK2_RS_V2 -> openFeelMk2
            SWIFT2_RS_V2 -> swift2
            JASMINE_RS_V2 -> jasmine
        }

    val displayName: String get() = spec.displayName
    val shortLabel: String get() = spec.shortLabel
    val family: ProtocolFamily get() = spec.family
    val gatt: GattProfile get() = spec.gatt
    val batteryLayout: BatteryLayout get() = spec.batteryLayout

    /** What this model offers under [firmware]; null means the firmware has not been read. */
    fun capabilities(firmware: FieldValue.Firmware? = null): DeviceCapabilities = spec.capabilities(firmware)

    /** The vendor app's name for an equalizer preset on this model, when it gives one. */
    fun equalizerPresetLabel(preset: String): String? = spec.equalizerPresetLabels[preset]

    companion object {
        /** The entry with this [name], as carried across processes; null for anything else. */
        fun fromName(name: String?): ModelProfile? = entries.firstOrNull { it.name == name }

        /**
         * Name-only evidence, for paired devices and audio connections that carry no advertisement. The name must look
         * like a ROSE device; a model number then decides, and a brand word only when the name has no model number.
         */
        fun match(name: String?): ModelProfile? {
            val normalized = name?.trim()?.lowercase() ?: return null
            if (!normalized.startsWith(ROSE_NAME_PREFIX) && nameBrands.none(normalized::contains)) return null
            val compact = normalized.filter(Char::isLetterOrDigit)
            val matchingNumbers = entries.filter { profile ->
                val number = profile.spec.nameModelNumber.filter(Char::isLetterOrDigit)
                val start = compact.indexOf(number)
                val end = start + number.length
                start >= 0 && (end == compact.length || !number.last().isDigit() || !compact[end].isDigit())
            }
            // Shared numbers such as MK2 need their brand; a bare number cannot identify a hardware branch.
            return matchingNumbers.firstOrNull { normalized.contains(it.spec.nameBrand) }
                ?: matchingNumbers.singleOrNull { candidate -> !candidate.spec.nameRequiresModel }
                ?: entries.firstOrNull { !it.spec.nameRequiresModel && normalized.contains(it.spec.nameBrand) }
        }

        /** [match]'s admission rule as a case-insensitive regular expression, for platform device-name filters. */
        val namePattern: String
            get() = nameBrands.joinToString("|", prefix = "(?is)${Regex.escape(ROSE_NAME_PREFIX)}.*|.*(?:", postfix = ").*") {
                Regex.escape(it)
            }

        private const val ROSE_NAME_PREFIX = "rose"
        private val nameBrands: List<String> get() = entries.map { it.spec.nameBrand }.distinct()
    }
}

object ModelIdentification {
    /** RoseLink puts every sorted LE company ID before every sorted payload. */
    fun normalizedHex(manufacturerData: Map<Int, ByteArray>): String {
        require(manufacturerData.keys.all { it in 0..65535 })
        val keys = manufacturerData.keys.sorted()
        val bytes = keys.flatMap { listOf(it.toByte(), (it shr 8).toByte()) } +
            keys.flatMap { manufacturerData.getValue(it).toList() }
        return bytes.toByteArray().hex()
    }

    /** RoseLink looks up the first 12, then 8, then 6 hex digits, so the longest registered prefix wins. */
    fun identify(manufacturerData: Map<Int, ByteArray>): ModelProfile? {
        val normalized = normalizedHex(manufacturerData)
        return ROSELINK_PREFIX_LENGTHS.firstNotNullOfOrNull { length -> advertisementPrefixes[normalized.take(length)] }
    }

    /** The classic address in [profile]'s advertisement, when its layout carries one. */
    fun classicAddress(normalizedManufacturerDataHex: String, profile: ModelProfile?): String? {
        val read = profile?.spec?.advertisedClassicAddress ?: return null
        return read(normalizedManufacturerDataHex)
    }

    private val advertisementPrefixes: Map<String, ModelProfile> by lazy {
        val prefixes = ModelProfile.entries.flatMap { profile -> profile.spec.advertisementPrefixes.map { it to profile } }
        prefixes.toMap().also { table -> check(table.size == prefixes.size) { "两个型号配置使用了相同的广播识别前缀" } }
    }
}

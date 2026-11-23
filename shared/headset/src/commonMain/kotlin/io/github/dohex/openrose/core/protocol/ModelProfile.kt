package io.github.dohex.openrose.core.protocol

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.DeviceSetting
import io.github.dohex.openrose.core.models.ModelSpec
import io.github.dohex.openrose.core.models.ROSELINK_PREFIX_LENGTHS
import io.github.dohex.openrose.core.models.agate
import io.github.dohex.openrose.core.models.agateX
import io.github.dohex.openrose.core.models.agumon
import io.github.dohex.openrose.core.models.astroX
import io.github.dohex.openrose.core.models.budsFeelAeNeo
import io.github.dohex.openrose.core.models.budsFeelMk2
import io.github.dohex.openrose.core.models.budsFreeI
import io.github.dohex.openrose.core.models.cambrian
import io.github.dohex.openrose.core.models.cambrianS
import io.github.dohex.openrose.core.models.ceramics
import io.github.dohex.openrose.core.models.ceramicsMk2
import io.github.dohex.openrose.core.models.ceramicsMk25736
import io.github.dohex.openrose.core.models.ceramicsMk28912f
import io.github.dohex.openrose.core.models.ceramicsU
import io.github.dohex.openrose.core.models.ceramicsUHsc2710
import io.github.dohex.openrose.core.models.ceramicsX
import io.github.dohex.openrose.core.models.ceramicsX7034
import io.github.dohex.openrose.core.models.coral
import io.github.dohex.openrose.core.models.earFeelI7
import io.github.dohex.openrose.core.models.earFeelIe2
import io.github.dohex.openrose.core.models.earFeelIeNeo
import io.github.dohex.openrose.core.models.earFreeI3
import io.github.dohex.openrose.core.models.earFreeI5
import io.github.dohex.openrose.core.models.explorer
import io.github.dohex.openrose.core.models.firstAngel
import io.github.dohex.openrose.core.models.furina
import io.github.dohex.openrose.core.models.gabumon
import io.github.dohex.openrose.core.models.headFeel
import io.github.dohex.openrose.core.models.hyacinth
import io.github.dohex.openrose.core.models.jasmine
import io.github.dohex.openrose.core.models.ky01
import io.github.dohex.openrose.core.models.magician
import io.github.dohex.openrose.core.models.mirror
import io.github.dohex.openrose.core.models.moka
import io.github.dohex.openrose.core.models.opal
import io.github.dohex.openrose.core.models.openFeelDual
import io.github.dohex.openrose.core.models.openFeelL
import io.github.dohex.openrose.core.models.openFeelLdac
import io.github.dohex.openrose.core.models.openFeelLhdc
import io.github.dohex.openrose.core.models.openFeelMk2
import io.github.dohex.openrose.core.models.openFun
import io.github.dohex.openrose.core.models.openFunTouch
import io.github.dohex.openrose.core.models.openLink
import io.github.dohex.openrose.core.models.patamon
import io.github.dohex.openrose.core.models.speakFeel2
import io.github.dohex.openrose.core.models.speakFree
import io.github.dohex.openrose.core.models.sportFeel
import io.github.dohex.openrose.core.models.sportFeel8912f
import io.github.dohex.openrose.core.models.swift
import io.github.dohex.openrose.core.models.swift2
import io.github.dohex.openrose.core.models.tailmon
import io.github.dohex.openrose.core.models.zircon
import kotlinx.collections.immutable.toPersistentSet

data class GattProfile(val serviceUuid: String, val writeUuid: String, val notifyUuid: String)

/** A control-frame family. Its codec owns framing and field semantics; each model keeps its own capabilities. */
enum class ProtocolFamily(val displayName: String) {
    ZT("ZT"),
    RS_COMMON_V2("RS Common V2"),
    F8("F8"),
    RS_COMMON("RS Common"),
    ;

    /** Fields this family's reader understands; this is not a list of enabled setting operations. */
    val readableFields: Set<DeviceField>
        get() = when (this) {
            ZT -> ZtReply.commands.values.flatMap { it.fields }.toSet()
            RS_COMMON_V2 -> rsCommonV2Fields.values.flatten().toSet()
            F8 -> f8Fields.values.flatten().toSet()
            RS_COMMON -> rsCommonFields.values.flatten().toSet()
        }

    /** Prompt volume levels this family's frames can carry, before any model or firmware limit. */
    internal val promptVolumeLevels: IntRange
        get() = when (this) {
            ZT -> 1..5
            RS_COMMON_V2 -> 0..5
            F8 -> 0..9
            RS_COMMON -> 1..5
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
    I3_ZT,
    HEADFEEL_ZT,
    AGATE_F8,
    AGATE_X_F8,
    CERAMICS_F8,
    CERAMICS_X_F8,
    CERAMICS_X_7034_F8,
    EXPLORER_F8,
    BUDSFREE_I_RS_COMMON,
    CAMBRIAN_RS_COMMON,
    OPENFEEL_LDAC_RS_COMMON,
    OPENFEEL_LHDC_RS_COMMON,
    OPENFEEL_DUAL_RS_COMMON,
    OPENFUN_RS_COMMON,
    OPENFUN_TOUCH_RS_COMMON,
    SPORTFEEL_RS_COMMON,
    SWIFT_RS_COMMON,
    OPENLINK_RS_COMMON,
    CAMBRIAN_S_RS_COMMON,
    CERAMICS_MK2_RS_V2,
    CERAMICS_MK2_8912F_RS_V2,
    CERAMICS_MK2_5736_RS_V2,
    CERAMICS_U_RS_V2,
    CERAMICS_U_HSC_2710_RS_V2,
    SPEAKFREE_RS_V2,
    MAGICIAN_RS_V2,
    FURINA_RS_V2,
    ZIRCON_RS_V2,
    HYACINTH_RS_V2,
    ASTRO_X_RS_V2,
    OPAL_RS_V2,
    MIRROR_RS_V2,
    KY01_RS_V2,
    CORAL_RS_V2,
    PATAMON_RS_V2,
    TAILMON_RS_V2,
    GABUMON_RS_V2,
    AGUMON_RS_V2,
    SPEAKFEEL2_RS_V2,
    OPENFEEL_L_RS_V2,
    FIRST_ANGEL_RS_V2,
    BUDSFEEL_AE_NEO_RS_V2,
    EARFEEL_IE_NEO_RS_V2,
    EARFEEL_IE2_RS_V2,
    SPORTFEEL_8912F_RS_V2,
    MOKA_RS_V2,
    ;

    internal val spec: ModelSpec
        get() = when (this) {
            I5_ZT -> earFreeI5
            I7_RS_V2 -> earFeelI7
            BUDSFEEL_MK2_RS_V2 -> budsFeelMk2
            OPENFEEL_MK2_RS_V2 -> openFeelMk2
            SWIFT2_RS_V2 -> swift2
            JASMINE_RS_V2 -> jasmine
            I3_ZT -> earFreeI3
            HEADFEEL_ZT -> headFeel
            AGATE_F8 -> agate
            AGATE_X_F8 -> agateX
            CERAMICS_F8 -> ceramics
            CERAMICS_X_F8 -> ceramicsX
            CERAMICS_X_7034_F8 -> ceramicsX7034
            EXPLORER_F8 -> explorer
            BUDSFREE_I_RS_COMMON -> budsFreeI
            CAMBRIAN_RS_COMMON -> cambrian
            OPENFEEL_LDAC_RS_COMMON -> openFeelLdac
            OPENFEEL_LHDC_RS_COMMON -> openFeelLhdc
            OPENFEEL_DUAL_RS_COMMON -> openFeelDual
            OPENFUN_RS_COMMON -> openFun
            OPENFUN_TOUCH_RS_COMMON -> openFunTouch
            SPORTFEEL_RS_COMMON -> sportFeel
            SWIFT_RS_COMMON -> swift
            OPENLINK_RS_COMMON -> openLink
            CAMBRIAN_S_RS_COMMON -> cambrianS
            CERAMICS_MK2_RS_V2 -> ceramicsMk2
            CERAMICS_MK2_8912F_RS_V2 -> ceramicsMk28912f
            CERAMICS_MK2_5736_RS_V2 -> ceramicsMk25736
            CERAMICS_U_RS_V2 -> ceramicsU
            CERAMICS_U_HSC_2710_RS_V2 -> ceramicsUHsc2710
            SPEAKFREE_RS_V2 -> speakFree
            MAGICIAN_RS_V2 -> magician
            FURINA_RS_V2 -> furina
            ZIRCON_RS_V2 -> zircon
            HYACINTH_RS_V2 -> hyacinth
            ASTRO_X_RS_V2 -> astroX
            OPAL_RS_V2 -> opal
            MIRROR_RS_V2 -> mirror
            KY01_RS_V2 -> ky01
            CORAL_RS_V2 -> coral
            PATAMON_RS_V2 -> patamon
            TAILMON_RS_V2 -> tailmon
            GABUMON_RS_V2 -> gabumon
            AGUMON_RS_V2 -> agumon
            SPEAKFEEL2_RS_V2 -> speakFeel2
            OPENFEEL_L_RS_V2 -> openFeelL
            FIRST_ANGEL_RS_V2 -> firstAngel
            BUDSFEEL_AE_NEO_RS_V2 -> budsFeelAeNeo
            EARFEEL_IE_NEO_RS_V2 -> earFeelIeNeo
            EARFEEL_IE2_RS_V2 -> earFeelIe2
            SPORTFEEL_8912F_RS_V2 -> sportFeel8912f
            MOKA_RS_V2 -> moka
        }

    val displayName: String get() = spec.displayName
    val shortLabel: String get() = spec.shortLabel
    val family: ProtocolFamily get() = spec.protocol.family
    val gatt: GattProfile get() = spec.gatt
    val batteryLayout: BatteryLayout get() = spec.batteryLayout

    /** Control choices derived from the facts this particular headset session has confirmed. */
    fun capabilities(readings: Map<DeviceField, FieldReading>): DeviceCapabilities = CapabilityFacts(readings).resolve(this)

    /**
     * What this model offers under [firmware]; null means the firmware has not been read. A setting that has no
     * choices to offer is not writable here, so callers never re-check whether its options are empty.
     */
    fun capabilities(firmware: FieldValue.Firmware? = null, gameMode: Boolean? = null): DeviceCapabilities {
        val capabilities = spec.capabilities(firmware)
        val gamePresets = spec.gameEqualizerPresets
        val offered = if (gamePresets == null) {
            capabilities
        } else {
            val eq = requireNotNull(capabilities.equalizer)
            val presets = when (gameMode) {
                true -> gamePresets
                false -> eq.presets
                null -> eq.presets.intersect(gamePresets)
            }
            capabilities.copy(equalizer = eq.copy(presets = presets.toPersistentSet()))
        }
        return offered.withoutWritesWithoutOptions()
    }

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
            val words = nameTokens(normalized)
            if (!normalized.startsWith(ROSE_NAME_PREFIX) && words.none { it in compactBrands }) return null
            val numbered = entries.filter { profile ->
                profile.spec.nameModelNumber.compact() in words && (!profile.spec.nameRequiresModel || profile.hasBrand(words))
            }
            if (numbered.isNotEmpty()) {
                // An explicit generation (MK2 / Swift 2) takes precedence over a bare retail brand.
                val specific = numbered.filter {
                    it.spec.nameModelNumber.filter(Char::isLetterOrDigit) != it.spec.nameBrand.filter(Char::isLetterOrDigit)
                }.ifEmpty { numbered }
                val longest = specific.maxOf { it.spec.nameModelNumber.filter(Char::isLetterOrDigit).length }
                val candidates = specific.filter { it.spec.nameModelNumber.filter(Char::isLetterOrDigit).length == longest }
                val branded = candidates.filter { it.hasBrand(words) }
                return (branded.ifEmpty { candidates }).singleOrNull()
            }
            val branded = entries.filter { profile ->
                !normalized.any(Char::isDigit) && !profile.spec.nameRequiresModel && profile.hasBrand(words)
            }
            return branded.singleOrNull()
        }

        private val ModelProfile.brands: List<String> get() = listOf(spec.nameBrand) + spec.nameBrandAliases

        private fun String.compact() = filter(Char::isLetterOrDigit)
        private fun ModelProfile.hasBrand(words: Set<String>) = brands.any { it.compact() in words }

        /** Whole device-name words, including multiword brands and unseparated forms such as EarFeeli5. */
        private fun nameTokens(normalized: String): Set<String> {
            val retail = normalized.removePrefix("roseselsa").removePrefix(ROSE_NAME_PREFIX)
            val parts = retail.split(nameSeparators).filter(String::isNotEmpty)
            val words = mutableSetOf<String>()
            for (start in parts.indices) {
                val joined = StringBuilder()
                for (end in start until parts.size) {
                    joined.append(parts[end])
                    // "Swift 20" must not fall back to "Swift" or "Swift 2".
                    if (parts.getOrNull(end + 1)?.first()?.isDigit() != true) words += joined.toString()
                }
            }
            for (word in words.toList()) {
                for (brand in compactBrands.filter(word::startsWith)) {
                    val suffix = word.removePrefix(brand)
                    if (suffix in modelNumbers || word in modelNumbers) words += brand
                    if (suffix in modelNumbers) words += suffix
                }
            }
            return words
        }

        private val nameSeparators = Regex("[^\\p{L}\\p{N}]+")
        private val compactBrands by lazy { nameBrands.map { it.compact() }.toSet() }
        private val modelNumbers by lazy { entries.map { it.spec.nameModelNumber.compact() }.toSet() }

        /** Admit word-bounded retail names and their concatenated model numbers to platform scans. */
        val namePattern: String by lazy {
            fun alternatives(words: Collection<String>) = words.joinToString("|") { word ->
                word.map { Regex.escape(it.toString()) }.joinToString("[\\s_-]*")
            }
            val retail = compactBrands + modelNumbers.filter { number -> compactBrands.any(number::startsWith) }
            val boundary = "[\\p{L}\\p{N}]"
            val brand = alternatives(retail)
            val model = alternatives(modelNumbers)
            "(?is)$ROSE_NAME_PREFIX.*|.*(?<!$boundary)(?:$brand)(?:$model)?(?!$boundary).*"
        }

        private const val ROSE_NAME_PREFIX = "rose"
        private val nameBrands: List<String> get() = entries.flatMap { it.brands }.distinct()
    }
}

/** Confirmed readings that can change the controls offered by a model. */
internal data class CapabilityFacts(val firmware: FieldValue.Firmware?, val gameMode: Boolean?) {
    constructor(
        readings: Map<DeviceField, FieldReading>,
        firmware: FieldValue.Firmware? = (readings[DeviceField.FIRMWARE] as? FieldReading.Known)?.value as? FieldValue.Firmware,
    ) : this(
        firmware,
        ((readings[DeviceField.GAME_MODE] as? FieldReading.Known)?.value as? FieldValue.Toggle)?.value,
    )

    fun resolve(profile: ModelProfile): DeviceCapabilities = profile.capabilities(firmware, gameMode)
}

/**
 * A setting chosen from a set of options is writable only while that set is not empty; readable fields are
 * unaffected. This is the one place that rule is decided.
 */
private fun DeviceCapabilities.withoutWritesWithoutOptions(): DeviceCapabilities {
    // Null or empty means the model offers nothing to choose from; a setting absent from the table has no options.
    val options: Map<DeviceSetting, Collection<*>?> = mapOf(
        DeviceSetting.NOISE_MODE to noiseControl?.modes,
        DeviceSetting.NOISE_CYCLE to noiseControl?.cycleModes,
        DeviceSetting.ANC_LEVEL to noiseControl?.ancLevel?.levels,
        DeviceSetting.TRANSPARENCY_LEVEL to noiseControl?.transparencyLevel?.levels,
        DeviceSetting.EQ to equalizer?.presets,
        DeviceSetting.CUSTOM_EQ to equalizer?.custom?.bandsHz,
        DeviceSetting.TOUCH_BINDING to touchControl?.takeIf { it.slots.isNotEmpty() }?.actions,
        DeviceSetting.TAP_BINDING to tapControl?.takeIf { it.slots.isNotEmpty() }?.actions,
        DeviceSetting.AUDIO_MODE to audioControl?.modes,
        DeviceSetting.PROMPT_VOLUME to promptVolumeLevels,
        DeviceSetting.PROMPT_LANGUAGE to promptLanguages,
        DeviceSetting.GAIN to gainLevels,
        DeviceSetting.HEARING_PROTECTION to hearingProtectionLevels,
        DeviceSetting.BASS_BOOST to bassBoostLevels,
        DeviceSetting.SPATIAL_MODE to spatialModes,
        DeviceSetting.SPATIAL_CONTENT to spatialContents,
    )
    val writable = writableSettings.filter { options[it]?.isNotEmpty() ?: (it !in options) }
    return if (writable.size == writableSettings.size) this else copy(writableSettings = writable.toPersistentSet())
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

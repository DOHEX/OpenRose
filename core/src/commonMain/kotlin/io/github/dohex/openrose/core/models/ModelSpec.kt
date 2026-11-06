package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.GattProfile
import io.github.dohex.openrose.core.protocol.ProtocolFamily
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * Everything one 型号配置 decides: how it is recognized, which protocol family and transport it uses, how it reports
 * battery, and what it offers under each firmware. Every value is explicit, so a new model never inherits another's.
 */
internal class ModelSpec(
    val displayName: String,
    val shortLabel: String,
    val family: ProtocolFamily,
    /** Lowercase normalized manufacturer-data prefixes, each as long as one of [ROSELINK_PREFIX_LENGTHS]. */
    val advertisementPrefixes: List<String>,
    /** Lowercase brand word that admits a device name, and decides this model when the name has no model number. */
    val nameBrand: String,
    /** Lowercase model number that decides this model in a device name. */
    val nameModelNumber: String,
    /** A brand alone is ambiguous with another retail model. */
    val nameRequiresModel: Boolean = false,
    val gatt: GattProfile,
    /** RFCOMM service of the control link over classic audio; null when the model is controlled over BLE only. */
    val sppControlUuid: String?,
    /** Pause between two control writes on one link. */
    val writeIntervalMillis: Long,
    /** Reads the classic address from normalized manufacturer data; null when the advertisement carries none. */
    val advertisedClassicAddress: ((String) -> String?)?,
    val batteryLayout: BatteryLayout,
    /** The vendor app's names for equalizer presets. */
    val equalizerPresetLabels: Map<String, String>,
    val capabilities: (FieldValue.Firmware?) -> DeviceCapabilities,
) {
    init {
        require(
            advertisementPrefixes.isNotEmpty() &&
                advertisementPrefixes.all { prefix -> prefix.length in ROSELINK_PREFIX_LENGTHS && prefix.all(::isLowerHex) },
        ) { "$displayName 的广播识别前缀须为 ${ROSELINK_PREFIX_LENGTHS.joinToString("/")} 位小写十六进制" }
        require(nameBrand.isNotBlank() && nameBrand == nameBrand.lowercase()) { "$displayName 的品牌词须为小写" }
        require(nameModelNumber.isNotBlank() && nameModelNumber == nameModelNumber.lowercase()) { "$displayName 的型号词须为小写" }
        require(writeIntervalMillis >= 0) { "$displayName 的写入间隔不能为负" }
    }
}

/** RoseLink matches the first 12, then 8, then 6 hex digits of the normalized manufacturer data. */
internal val ROSELINK_PREFIX_LENGTHS = listOf(12, 8, 6)

/** The four modes both supported families encode for noise control and its cycle. */
internal val basicNoiseModes: PersistentSet<String> = persistentSetOf("normal", "transparency", "wind", "anc")

/** Single, double, triple and long press on each earbud. */
internal val earbudTouchSlots: PersistentSet<TouchSlot> = listOf("left", "right").flatMap { side ->
    listOf("single", "double", "triple", "long").map { gesture -> TouchSlot(side, gesture) }
}.toPersistentSet()

private fun isLowerHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

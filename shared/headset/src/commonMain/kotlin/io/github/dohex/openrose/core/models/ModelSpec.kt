package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.DeviceCapabilities
import io.github.dohex.openrose.core.TouchSlot
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.FieldValue
import io.github.dohex.openrose.core.protocol.GattProfile
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

internal class ModelSpec(
    val displayName: String,
    val shortLabel: String,
    val versionName: String? = null,
    val protocol: ModelProtocol,
    val advertisementPrefixes: List<String>,
    val nameBrand: String,
    val nameModelNumber: String,
    val nameRequiresModel: Boolean = false,
    val nameBrandAliases: List<String> = emptyList(),
    val gatt: GattProfile,
    val sppControlUuid: String?,
    val writeIntervalMillis: Long,
    val advertisedClassicAddress: ((String) -> String?)?,
    val batteryLayout: BatteryLayout,
    val equalizerPresetLabels: Map<String, String>,
    val capabilities: (FieldValue.Firmware?) -> DeviceCapabilities,
    val gameEqualizerPresets: Set<String>? = null,
) {
    init {
        require(
            advertisementPrefixes.isNotEmpty() &&
                advertisementPrefixes.all { prefix -> prefix.length in ROSELINK_PREFIX_LENGTHS && prefix.all(::isLowerHex) },
        ) { "$displayName 的广播识别前缀须为 ${ROSELINK_PREFIX_LENGTHS.joinToString("/")} 位小写十六进制" }
        require(nameBrand.isNotBlank() && nameBrand == nameBrand.lowercase()) { "$displayName 的品牌词须为小写" }
        require(nameModelNumber.isNotBlank() && nameModelNumber == nameModelNumber.lowercase()) { "$displayName 的型号词须为小写" }
        require(writeIntervalMillis >= 0) { "$displayName 的写入间隔不能为负" }
        require(versionName == null || versionName.isNotBlank()) { "$displayName 的版本名不能为空白" }
    }
}

internal val ROSELINK_PREFIX_LENGTHS = listOf(12, 8, 6)

internal val basicNoiseModes: PersistentSet<String> = persistentSetOf("normal", "transparency", "wind", "anc")

internal val earbudTouchSlots: PersistentSet<TouchSlot> = listOf("left", "right").flatMap { side ->
    listOf("single", "double", "triple", "long").map { gesture -> TouchSlot(side, gesture) }
}.toPersistentSet()

private fun isLowerHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

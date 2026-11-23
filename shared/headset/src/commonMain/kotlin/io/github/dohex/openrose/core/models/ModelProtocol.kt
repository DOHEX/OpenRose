package io.github.dohex.openrose.core.models

import io.github.dohex.openrose.core.protocol.ProtocolFamily
import io.github.dohex.openrose.core.protocol.RsCommonInit

internal sealed class ModelProtocol(val family: ProtocolFamily, val refreshEqualizerAfterGameMode: Boolean = false) {
    class Zt(
        val initKeys: List<String> = emptyList(),
        val threeByteNoise: Boolean = false,
        val independentMultipoint: Boolean = false,
        val levelWriteOffset: Int = 0,
        val promptVolumeDirect: Boolean = false,
    ) : ModelProtocol(ProtocolFamily.ZT)

    class F8(
        val extendedInit: Boolean = false,
        val noiseNormalWire: Int = 3,
        val eqAtTwo: String? = "rock",
    ) : ModelProtocol(ProtocolFamily.F8)

    class RsCommon(
        val initialization: RsCommonInit = RsCommonInit.DEFAULT,
        val touchActions: Map<String, Int> = mapOf(
            "none" to 0,
            "play_pause" to 1,
            "previous_track" to 2,
            "next_track" to 3,
            "volume_up" to 4,
            "volume_down" to 5,
        ),
    ) : ModelProtocol(ProtocolFamily.RS_COMMON) {
        init {
            require(touchActions.values.all { it in 0..9 }) { "RS Common 触控动作码须为单个十进制数字" }
        }
    }

    class RsCommonV2(
        val infoIds: String?,
        val spatialNeedsEarAdaptive: Boolean = false,
        val synchronizeClockOnConnect: Boolean = false,
        refreshEqualizerAfterGameMode: Boolean = false,
    ) : ModelProtocol(ProtocolFamily.RS_COMMON_V2, refreshEqualizerAfterGameMode)
}

package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField
import io.github.dohex.openrose.core.protocol.FieldValue

/** What the 快捷面板 tile says about the headphones right now. */
data class QuickTileSummary(
    /** A ready control session exists. */
    val active: Boolean,
    /** Left and right earbud battery when known, "已连接" without a reading, otherwise the connection state. */
    val subtitle: String,
)

/** The 快捷面板 tile's state; "ready" is the same session readiness the 托盘面板 uses. */
fun quickTileSummary(state: ControlState): QuickTileSummary {
    val ready = state.activeDevice()?.takeIf { state.isReady(it) }
    return when {
        ready != null -> QuickTileSummary(active = true, subtitle = ready.earbudBatterySubtitle() ?: "已连接")
        state.isConnectingForTray() -> QuickTileSummary(active = false, subtitle = "正在连接…")
        else -> QuickTileSummary(active = false, subtitle = "未连接")
    }
}

/** Known earbud readings only, never the case; a single unit reports its one reading. Null when none is known. */
private fun DeviceState.earbudBatterySubtitle(): String? {
    val earbudReadings = profile.batteryReadings().filter { (_, field) ->
        field != DeviceField.CASE_BATTERY || profile.batteryLayout == BatteryLayout.SINGLE_UNIT
    }
    return earbudReadings.mapNotNull { (label, field) ->
        (knownValue(field) as? FieldValue.Number)?.value?.takeIf { it in 0..100 }?.let { "$label $it%" }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

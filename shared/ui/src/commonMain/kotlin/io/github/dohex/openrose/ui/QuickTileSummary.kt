package io.github.dohex.openrose.ui

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceState
import io.github.dohex.openrose.core.protocol.BatteryLayout
import io.github.dohex.openrose.core.protocol.DeviceField

data class QuickTileSummary(
    val active: Boolean,
    val subtitle: String,
)

fun quickTileSummary(state: ControlState): QuickTileSummary {
    val ready = state.activeDevice()?.takeIf { state.isReady(it) }
    return when {
        ready != null -> QuickTileSummary(active = true, subtitle = ready.earbudBatterySubtitle() ?: "已连接")
        state.isConnectingForTray() -> QuickTileSummary(active = false, subtitle = "正在连接…")
        else -> QuickTileSummary(active = false, subtitle = "未连接")
    }
}

private fun DeviceState.earbudBatterySubtitle(): String? {
    val earbudReadings = profile.batteryReadings().filter { (_, field) ->
        field != DeviceField.CASE_BATTERY || profile.batteryLayout == BatteryLayout.SINGLE_UNIT
    }
    return batterySummary(earbudReadings, showUnknown = false) { confirmedNumber(it) }
}

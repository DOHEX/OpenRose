package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

sealed interface FusionEntryDecision {
    data object System : FusionEntryDecision

    data class OpenRose(val deviceId: DeviceId) : FusionEntryDecision
}

object FusionEntry {
    fun resolve(state: ControlState?, settings: IntegrationSettings, address: String): FusionEntryDecision {
        state ?: return FusionEntryDecision.System
        if (settings.fusionSettingsDestination != FusionSettingsDestination.OPENROSE) return FusionEntryDecision.System
        val wanted = normalizeBluetoothAddress(address) ?: return FusionEntryDecision.System
        val known = buildList {
            state.device?.let { add(it.deviceId to it.classicAddress) }
            state.connectionTarget?.takeIf { it.profile != null }?.let { add(it.deviceId to it.classicAddress) }
            (state.candidates + state.bondedDevices).filter { it.profile != null }.forEach { add(it.deviceId to it.classicAddress) }
        }
        for ((deviceId, classic) in known) {
            val canonical = normalizeBluetoothAddress(deviceId.value) ?: continue
            if (wanted == canonical || wanted == normalizeBluetoothAddress(classic)) {
                return FusionEntryDecision.OpenRose(DeviceId(canonical))
            }
        }
        return FusionEntryDecision.System
    }
}

package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.transport.DeviceId
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

/** What the fusion device center's additional settings entry does for one Bluetooth address. */
sealed interface FusionEntryDecision {
    /** Leave the system's own settings page alone. */
    data object System : FusionEntryDecision

    /** Open OpenRose for the earphones that own this address; [deviceId] is the control device, not the alias. */
    data class OpenRose(val deviceId: DeviceId) : FusionEntryDecision
}

/**
 * Decides the settings entry from the module owner's own state: the user's persisted destination and the
 * ROSE earphones the owner knows (active, targeted, candidate, bonded) with their addresses. Without an
 * owner, or for any address that is not a known ROSE device, the system's entry is left alone.
 */
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
            // The control device must itself be a Bluetooth address: the application opens it by address.
            val canonical = normalizeBluetoothAddress(deviceId.value) ?: continue
            if (wanted == canonical || wanted == normalizeBluetoothAddress(classic)) {
                return FusionEntryDecision.OpenRose(DeviceId(canonical))
            }
        }
        return FusionEntryDecision.System
    }
}

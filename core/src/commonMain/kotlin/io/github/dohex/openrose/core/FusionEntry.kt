package io.github.dohex.openrose.core

import io.github.dohex.openrose.core.transport.DeviceId

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
    fun resolve(state: ControlState?, address: String): FusionEntryDecision {
        state ?: return FusionEntryDecision.System
        if (state.settings.fusionSettingsDestination != FusionSettingsDestination.OPENROSE) return FusionEntryDecision.System
        val wanted = normalizeAddress(address) ?: return FusionEntryDecision.System
        val known = buildList {
            state.device?.let { add(it.deviceId to it.classicAddress) }
            state.connectionTarget?.takeIf { it.profile != null }?.let { add(it.deviceId to it.classicAddress) }
            (state.candidates + state.bondedDevices).filter { it.profile != null }.forEach { add(it.deviceId to it.classicAddress) }
        }
        for ((deviceId, classic) in known) {
            // The control device must itself be a Bluetooth address: the application opens it by address.
            if (normalizeAddress(deviceId.value) == null) continue
            if (wanted == normalizeAddress(deviceId.value) || wanted == classic?.let(::normalizeAddress)) {
                return FusionEntryDecision.OpenRose(DeviceId(normalizeAddress(deviceId.value)!!))
            }
        }
        return FusionEntryDecision.System
    }

    /** Upper-case colon-separated form, or null when [address] is not a Bluetooth address. */
    fun normalizeAddress(address: String?): String? {
        val value = address?.trim()?.uppercase() ?: return null
        if (value.length != 17) return null
        for (index in value.indices) {
            if (index % 3 == 2) {
                if (value[index] != ':') return null
            } else if (value[index] !in '0'..'9' && value[index] !in 'A'..'F') {
                return null
            }
        }
        return value
    }
}

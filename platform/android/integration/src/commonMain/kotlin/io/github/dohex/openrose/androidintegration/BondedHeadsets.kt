package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

/**
 * The ROSE earphones the module owner knows among the system's bonded devices, by every Bluetooth address they answer
 * to. The system entries identify them even with no current session, as on the target source device of a headset
 * circulation (ADR 0017). Without a module owner (ordinary control) the host has no owner state and names none.
 */
object BondedHeadsets {
    fun addresses(state: ControlState): Set<String> = state.bondedDevices
        .filter { it.profile != null }
        .flatMap { listOf(it.deviceId.value, it.classicAddress) }
        .mapNotNullTo(linkedSetOf(), ::normalizeBluetoothAddress)
}

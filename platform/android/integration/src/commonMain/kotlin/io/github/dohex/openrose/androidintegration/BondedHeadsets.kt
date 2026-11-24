package io.github.dohex.openrose.androidintegration

import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.transport.normalizeBluetoothAddress

object BondedHeadsets {
    fun addresses(state: ControlState): Set<String> = state.bondedDevices
        .filter { it.profile != null }
        .flatMap { listOf(it.deviceId.value, it.classicAddress) }
        .mapNotNullTo(linkedSetOf(), ::normalizeBluetoothAddress)
}

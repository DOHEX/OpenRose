package io.github.dohex.openrose.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.transport.DeviceId

/** Bonded devices this app can control; the 未连接看板 and the 托盘面板 offer exactly these. */
internal fun ControlState.bondedRoseDevices(): List<DeviceCandidate> = bondedDevices.filter { it.profile != null }

/**
 * The 未连接看板's primary-device choice, shared by every surface of one host.
 * A manual choice lasts only while the bonded ROSE devices stay as they were when it was made.
 */
@Stable
class PrimaryDeviceSelection {
    private class Choice(val bonded: List<DeviceCandidate>, val deviceId: DeviceId)

    private var choice by mutableStateOf<Choice?>(null)

    /** The connection target, else the manual choice, else the first bonded ROSE device. */
    fun primary(state: ControlState): DeviceCandidate? {
        state.connectionTarget?.let { return it }
        val bonded = state.bondedRoseDevices()
        val chosen = choice?.takeIf { it.bonded == bonded }
            ?.let { current -> bonded.firstOrNull { it.deviceId == current.deviceId } }
        return chosen ?: bonded.firstOrNull()
    }

    /** What the dashboard's “切换” does before it connects. */
    fun select(state: ControlState, deviceId: DeviceId) {
        choice = Choice(state.bondedRoseDevices(), deviceId)
    }

    /** Forgets the manual choice; the 未连接看板 does so whenever its bonded list changes or it leaves composition. */
    internal fun clear() {
        choice = null
    }
}

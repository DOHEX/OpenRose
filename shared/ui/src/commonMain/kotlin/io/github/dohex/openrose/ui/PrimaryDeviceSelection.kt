package io.github.dohex.openrose.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dohex.openrose.core.ConnectionState
import io.github.dohex.openrose.core.ControlState
import io.github.dohex.openrose.core.DeviceCandidate
import io.github.dohex.openrose.core.transport.DeviceId

internal fun ControlState.bondedRoseDevices(): List<DeviceCandidate> = bondedDevices.filter { it.profile != null }

@Stable
class PrimaryDeviceSelection {
    private class Choice(val bonded: List<DeviceCandidate>, val deviceId: DeviceId)

    private var choice by mutableStateOf<Choice?>(null)

    fun primary(state: ControlState): DeviceCandidate? {
        state.connectionTarget?.let { return it }
        val bonded = state.bondedRoseDevices()
        val connectedId = when (val connection = state.connection) {
            is ConnectionState.Connecting -> connection.deviceId
            is ConnectionState.Connected -> connection.deviceId
            ConnectionState.Disconnected -> null
        }
        return if (connectedId != null) {
            bonded.firstOrNull { it.deviceId == connectedId } ?: state.candidates.firstOrNull { it.deviceId == connectedId }
        } else {
            val chosen = choice?.takeIf { it.bonded == bonded }
                ?.let { current -> bonded.firstOrNull { it.deviceId == current.deviceId } }
            chosen ?: bonded.firstOrNull()
        }
    }

    fun select(state: ControlState, deviceId: DeviceId) {
        choice = Choice(state.bondedRoseDevices(), deviceId)
    }

    internal fun clear() {
        choice = null
    }
}
